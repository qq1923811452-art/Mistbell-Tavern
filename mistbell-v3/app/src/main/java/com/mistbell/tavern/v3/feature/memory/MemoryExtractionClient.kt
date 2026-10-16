package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.core.network.EndpointPolicy
import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

fun interface MemoryCredentialResolver {
    fun resolve(handle: String): String?
}

data class MemoryExtractionInput(
    val conversationId: String,
    val turns: List<EligibleConversationTurn>,
    val textByTurnId: Map<String, String>,
) {
    init {
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank." }
        require(turns.isNotEmpty() && turns.size <= MAX_TURNS) { "Turn count is outside the supported range." }
        require(turns.map(EligibleConversationTurn::id).distinct().size == turns.size) {
            "Turn IDs must be unique."
        }
        require(turns.all { it.id in textByTurnId }) { "Every turn must have source text." }
        require(textByTurnId.values.all { it.isNotBlank() && it.length <= MAX_TURN_TEXT_CHARS }) {
            "Turn text is blank or exceeds the size limit."
        }
    }

    companion object {
        const val MAX_TURNS = 20
        const val MAX_TURN_TEXT_CHARS = 20_000
    }
}

data class ExtractedFactDraft(
    val subject: String,
    val predicate: String,
    val value: String,
    val sourceTurnId: String,
)

data class MemoryExtractionResult(val facts: List<ExtractedFactDraft>)

sealed interface MemoryExtractionOutcome {
    data class Success(val result: MemoryExtractionResult) : MemoryExtractionOutcome
    data class Failure(val safeMessage: String, val httpStatus: Int? = null) : MemoryExtractionOutcome
}

fun interface MemoryExtractionObserver {
    fun onFinished(outcome: MemoryExtractionOutcome)
}

/**
 * Uses only the explicitly selected MEMORY_EXTRACTION profile. This client has its own model,
 * endpoint and credential handle; it never falls back to the chat profile or includes response
 * bodies/credentials in failure messages.
 */
class MemoryExtractionClient(
    private val providers: ProviderProfileSet,
    private val credentials: MemoryCredentialResolver,
    client: OkHttpClient = OkHttpClient(),
) {
    private val httpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun extract(input: MemoryExtractionInput, observer: MemoryExtractionObserver): Call {
        val profile = providers.profileFor(ProviderKind.MEMORY_EXTRACTION)
            ?: throw IllegalStateException("No enabled memory-extraction provider is explicitly selected.")
        val validation = EndpointPolicy.validateBaseUrl(profile.baseUrl)
        val baseUrl = validation.normalizedBaseUrl
            ?: throw IllegalStateException(validation.reason ?: "Memory provider base URL is invalid.")
        require(profile.modelId.isNotBlank()) { "Memory-extraction model ID is required." }

        val uri = URI(baseUrl)
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT)
        val localHttp = uri.scheme.equals("http", ignoreCase = true) &&
            host != null && host in LOOPBACK_HOSTS
        val key = profile.credentialHandle?.takeIf(String::isNotBlank)?.let { handle ->
            credentials.resolve(handle)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalStateException("The selected memory-extraction credential is unavailable.")
        }
        if (key == null && !localHttp) {
            throw IllegalStateException("A credential is required for a remote memory-extraction provider.")
        }

        val turnsJson = JSONArray()
        input.turns.sortedBy(EligibleConversationTurn::ordinal).forEach { turn ->
            turnsJson.put(
                JSONObject()
                    .put("turn_id", turn.id)
                    .put("text", input.textByTurnId.getValue(turn.id)),
            )
        }
        val systemPrompt = """
            Extract only durable, explicit facts useful for future conversations.
            Return exactly one JSON object with a "facts" array. Each fact must have string fields
            "subject", "predicate", "value", and "source_turn_id". source_turn_id must be one of the
            supplied turn IDs. Do not infer uncertain details, invent facts, include secrets, or
            repeat transient small talk. Return {"facts":[]} when nothing qualifies.
        """.trimIndent()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONObject().put("conversation_id", input.conversationId).put("turns", turnsJson).toString(),
                ),
            )
        val requestBody = JSONObject()
            .put("model", profile.modelId)
            .put("stream", false)
            .put("messages", messages)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Content-Type", "application/json")
            .post(requestBody)
        if (key != null) builder.header("Authorization", "Bearer $key")
        val call = httpClient.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                observer.onFinished(
                    MemoryExtractionOutcome.Failure(
                        if (call.isCanceled()) "Memory extraction was cancelled."
                        else "Memory-extraction request failed.",
                    ),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { opened ->
                    if (!opened.isSuccessful) {
                        observer.onFinished(
                            MemoryExtractionOutcome.Failure(
                                safeMessage = "Memory-extraction provider returned HTTP ${opened.code}.",
                                httpStatus = opened.code,
                            ),
                        )
                        return
                    }
                    val body = opened.body
                    if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                        observer.onFinished(MemoryExtractionOutcome.Failure("Memory-extraction response is empty or too large."))
                        return
                    }
                    val parsed = try {
                        val envelope = JSONObject(body.string())
                        val choices = envelope.optJSONArray("choices")
                            ?: throw JSONException("Missing choices")
                        val content = choices.optJSONObject(0)?.optJSONObject("message")
                            ?.optString("content")?.takeIf(String::isNotBlank)
                            ?: throw JSONException("Missing message content")
                        parseFacts(content, input.turns.map(EligibleConversationTurn::id).toSet())
                    } catch (_: Exception) {
                        observer.onFinished(
                            MemoryExtractionOutcome.Failure("Memory-extraction response failed validation."),
                        )
                        return
                    }
                    observer.onFinished(MemoryExtractionOutcome.Success(parsed))
                }
            }
        })
        return call
    }

    private fun parseFacts(content: String, allowedTurnIds: Set<String>): MemoryExtractionResult {
        require(content.length <= MAX_RESPONSE_BYTES) { "Memory response is too large." }
        val factsJson = JSONObject(content).optJSONArray("facts")
            ?: throw JSONException("Missing facts array")
        require(factsJson.length() <= MAX_FACTS) { "Too many extracted facts." }
        val facts = (0 until factsJson.length()).map { index ->
            val fact = factsJson.optJSONObject(index) ?: throw JSONException("Fact must be an object")
            val subject = fact.optString("subject").trim()
            val predicate = fact.optString("predicate").trim()
            val value = fact.optString("value").trim()
            val sourceTurnId = fact.optString("source_turn_id").trim()
            require(subject.isNotBlank() && subject.length <= MAX_FIELD_CHARS)
            require(predicate.isNotBlank() && predicate.length <= MAX_FIELD_CHARS)
            require(value.isNotBlank() && value.length <= MAX_FIELD_CHARS)
            require(sourceTurnId in allowedTurnIds) { "Fact refers to an unknown source turn." }
            ExtractedFactDraft(subject, predicate, value, sourceTurnId)
        }
        return MemoryExtractionResult(facts)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private const val MAX_RESPONSE_BYTES = 256 * 1024L
        private const val MAX_FACTS = 100
        private const val MAX_FIELD_CHARS = 2_000
    }
}
