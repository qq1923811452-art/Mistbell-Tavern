package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.core.network.EndpointPolicy
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
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val THINK_BLOCK_REGEX =
    Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Removes closed reasoning blocks so their braces cannot break JSON extraction. */
internal fun stripThinkingBlocks(text: String): String = text.replace(THINK_BLOCK_REGEX, "")

private val TURN_ALIAS_PATTERN = Regex("^[tT]?(\\d+)$")

/** Accepts the real turn ID or its 1-based short alias (`t1` / `1`) and maps back to the ID. */
private fun resolveSourceTurnId(
    raw: String,
    turnIdsInOrder: List<String>,
): String? =
    if (raw in turnIdsInOrder) {
        raw
    } else {
        TURN_ALIAS_PATTERN.matchEntire(raw)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.let { turnIdsInOrder.getOrNull(it - 1) }
    }

fun interface MemoryCredentialResolver {
    fun resolve(handle: String): String?
}

/**
 * Formats a completed chat pair without allowing a very long user message to erase the
 * assistant boundary. The total is bounded while both sides retain as much text as possible.
 */
fun formatMemoryExtractionTurn(
    userText: String,
    assistantText: String,
): String {
    val userPrefix = "User: "
    val assistantPrefix = "\nAssistant: "
    val contentBudget =
        MemoryExtractionInput.MAX_TURN_TEXT_CHARS -
            userPrefix.length - assistantPrefix.length
    require(contentBudget >= 0) { "Turn text limit is too small for role boundaries." }

    var userBudget = minOf(userText.length, contentBudget / 2)
    var assistantBudget = minOf(assistantText.length, contentBudget - userBudget)
    userBudget = minOf(userText.length, contentBudget - assistantBudget)

    return userPrefix + userText.take(userBudget) +
        assistantPrefix + assistantText.take(assistantBudget)
}

data class MemoryExtractionInput(
    val conversationId: String,
    val turns: List<EligibleConversationTurn>,
    val textByTurnId: Map<String, String>,
    /** Durable source-message times; extraction completion order must not define narrative order. */
    val turnObservedAtEpochMillis: Map<String, Long> = emptyMap(),
) {
    init {
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank." }
        require(turns.isNotEmpty() && turns.size <= MAX_TURNS) { "Turn count is outside the supported range." }
        require(turns.map(EligibleConversationTurn::id).distinct().size == turns.size) {
            "Turn IDs must be unique."
        }
        require(turns.all { it.id in textByTurnId }) { "Every turn must have source text." }
        require(
            turnObservedAtEpochMillis.keys.all { id -> turns.any { it.id == id } } &&
                turnObservedAtEpochMillis.values.all { it >= 0L },
        ) {
            "Observation times must be nonnegative and belong to source turns."
        }
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
    client: OkHttpClient = SHARED_HTTP_CLIENT,
    readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    private val onDiagnostic: (String, String) -> Unit = { _, _ -> },
) : MemoryFactExtractor {
    private val socketReadTimeoutMillis =
        readTimeoutMillis.also { require(it > 0L) { "Memory extraction read timeout must be positive." } }

    // A non-streamed completion sends no bytes until the whole answer is generated, so the socket
    // read timeout — not the call timeout — decides whether a slow but healthy provider succeeds.
    private val httpClient =
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(socketReadTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(MemoryExtractionTimeouts.CALL_MILLIS, TimeUnit.MILLISECONDS)
            .build()

    override fun extract(
        input: MemoryExtractionInput,
        observer: MemoryExtractionObserver,
    ): Call {
        val profile =
            providers.profileFor(ProviderKind.MEMORY_EXTRACTION)
                ?: error("No enabled memory-extraction provider is explicitly selected.")
        val validation = EndpointPolicy.validateBaseUrl(profile.baseUrl)
        val baseUrl =
            validation.normalizedBaseUrl
                ?: error(validation.reason ?: "Memory provider base URL is invalid.")
        require(profile.modelId.isNotBlank()) { "Memory-extraction model ID is required." }

        val uri = URI(baseUrl)
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT)
        val localHttp =
            uri.scheme.equals("http", ignoreCase = true) &&
                host != null && host in LOOPBACK_HOSTS
        val key =
            profile.credentialHandle?.takeIf(String::isNotBlank)?.let { handle ->
                credentials.resolve(handle)?.trim()?.takeIf(String::isNotEmpty)
                    ?: error("The selected memory-extraction credential is unavailable.")
            }
        if (key == null && !localHttp) {
            error("A credential is required for a remote memory-extraction provider.")
        }

        val messages = buildMessages(input)
        val requestBody =
            JSONObject()
                .put("model", profile.modelId)
                .put("stream", false)
                .put("temperature", MEMORY_EXTRACTION_TEMPERATURE)
                .put("messages", messages)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
        val builder =
            Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Content-Type", "application/json")
                .post(requestBody)
        if (key != null) builder.header("Authorization", "Bearer $key")
        report(
            "memory.extraction.request.started",
            "model=${profile.modelId}; host=${host.orEmpty()}; " +
                "turns=${input.turns.size}; chars=${input.turns.sumOf { input.textByTurnId.getValue(it.id).length }}",
        )
        val startedAtNanos = System.nanoTime()
        val call = httpClient.newCall(builder.build())
        val finished = AtomicBoolean(false)

        fun finish(outcome: MemoryExtractionOutcome) {
            if (finished.compareAndSet(false, true)) observer.onFinished(outcome)
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    finish(requestFailure(call, e, startedAtNanos))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    // OkHttp does not call onFailure if reading inside onResponse throws.
                    // Close the response before notifying consumers, and never mistake a consumer
                    // exception for a transport failure (or deliver a second completion).
                    val httpFailure =
                        response.takeUnless { it.isSuccessful }?.let {
                            MemoryExtractionOutcome.Failure(
                                safeMessage = "Memory-extraction provider returned HTTP ${it.code}.",
                                httpStatus = it.code,
                            )
                        }
                    val outcome =
                        try {
                            response.use { opened -> readOutcome(opened, input) }
                        } catch (failure: IOException) {
                            httpFailure ?: requestFailure(call, failure, startedAtNanos)
                        } catch (_: Exception) {
                            httpFailure ?: MemoryExtractionOutcome.Failure("Memory-extraction response could not be processed.")
                        }
                    finish(outcome)
                }
            },
        )
        return call
    }

    private fun buildMessages(input: MemoryExtractionInput): JSONArray {
        val turnsJson = JSONArray()
        input.turns.sortedBy(EligibleConversationTurn::ordinal).forEachIndexed { index, turn ->
            turnsJson.put(
                JSONObject()
                    .put("turn_id", turn.id)
                    .put("alias", "t${index + 1}")
                    .put("text", input.textByTurnId.getValue(turn.id)),
            )
        }
        val systemPrompt =
            """
            Extract only durable, explicit facts useful for future conversations.
            Return exactly one JSON object with a "facts" array. Each fact must have string fields
            "subject", "predicate", "value", and "source_turn_id". source_turn_id must be one of the
            supplied turn IDs or the short alias (t1, t2, ...) printed next to each turn. Do not infer
            uncertain details, invent facts, include secrets, or repeat transient small talk.
            Return {"facts":[]} when nothing qualifies.
            Write atomic facts: subject is a short entity name, predicate is a short relation,
            value is a concise object. Do not put a full sentence, question, or yes/no answer
            into predicate or value. Skip transient states and assistant suggestions.
            Return the JSON object directly as the message content, without Markdown code
            fences, prefixes, or commentary.
            """.trimIndent()
        return JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONObject().put("conversation_id", input.conversationId).put("turns", turnsJson).toString(),
                ),
            )
    }

    /**
     * Socket and overall call expiry are timeouts, not proof of a healthy or generating provider.
     * OkHttp's overall expiry also marks the call cancelled, so recognize it before cancellation.
     * Exception messages are never logged because they can contain request URLs.
     */
    private fun requestFailure(
        call: Call,
        error: IOException,
        startedAtNanos: Long,
    ): MemoryExtractionOutcome.Failure {
        val timedOut =
            error is SocketTimeoutException ||
                (error is InterruptedIOException && error.message == "timeout")
        report(
            "memory.extraction.request_failed",
            "kind=${error::class.java.simpleName}; timedOut=$timedOut; " +
                "elapsedMs=${(System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI}",
        )
        return MemoryExtractionOutcome.Failure(
            when {
                timedOut -> "Memory-extraction request timed out."
                call.isCanceled() -> "Memory extraction was cancelled."
                else -> "Memory-extraction request failed."
            },
        )
    }

    /** Diagnostics are best-effort: a full disk must not turn a valid response into a failure. */
    private fun report(
        event: String,
        detail: String,
    ) {
        runCatching { onDiagnostic(event, detail) }
    }

    /** Only known protocol labels are logged; arbitrary remote tokens can contain private text. */
    private fun safeProviderErrorCode(response: Response): String? =
        runCatching {
            val text = readErrorBody(response)
            val error = text?.let { JSONObject(it).optJSONObject("error") }
            val raw = error?.opt("code") as? String
            val type = error?.opt("type") as? String
            when {
                raw in SAFE_ERROR_CODES -> raw
                type in SAFE_ERROR_CODES -> type
                error != null -> "unrecognized"
                else -> null
            }
        }.getOrNull()

    /** Diagnostic reads have both a byte cap and a fixed total deadline (not per-read timeouts). */
    private fun readErrorBody(response: Response): String? {
        val body = response.body
        if (body == null || body.contentLength() > MAX_ERROR_BODY_BYTES) return null
        val source = body.source()
        val timeout = source.timeout()
        val hadDeadline = timeout.hasDeadline()
        val oldDeadline = if (hadDeadline) timeout.deadlineNanoTime() else 0L
        val diagnosticDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ERROR_BODY_TIMEOUT_MILLIS)
        timeout.deadlineNanoTime(if (hadDeadline) minOf(oldDeadline, diagnosticDeadline) else diagnosticDeadline)
        return try {
            val buffer = okio.Buffer()
            while (buffer.size <= MAX_ERROR_BODY_BYTES) {
                if (System.nanoTime() - diagnosticDeadline >= 0L) throw InterruptedIOException("timeout")
                timeout.throwIfReached()
                val remaining = MAX_ERROR_BODY_BYTES + 1 - buffer.size
                if (source.read(buffer, minOf(RESPONSE_READ_CHUNK_BYTES, remaining)) == -1L) break
            }
            if (buffer.size > MAX_ERROR_BODY_BYTES) null else buffer.readUtf8().takeIf(String::isNotBlank)
        } finally {
            if (hadDeadline) timeout.deadlineNanoTime(oldDeadline) else timeout.clearDeadline()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readOutcome(
        response: Response,
        input: MemoryExtractionInput,
    ): MemoryExtractionOutcome {
        if (!response.isSuccessful) {
            val code = safeProviderErrorCode(response)
            report("memory.extraction.http_error", "status=${response.code}; code=${code ?: "none"}")
            return MemoryExtractionOutcome.Failure(
                safeMessage = "Memory-extraction provider returned HTTP ${response.code}.",
                httpStatus = response.code,
            )
        }
        val responseText = readBoundedBody(response)
        return if (responseText == null) {
            report("memory.extraction.response_invalid", "reason=empty_or_oversized")
            MemoryExtractionOutcome.Failure("Memory-extraction response is empty or too large.")
        } else {
            try {
                val envelope = JSONObject(responseText)
                val choices =
                    envelope.optJSONArray("choices")
                        ?: throw JSONException("Missing choices")
                val content =
                    choices.optJSONObject(0)?.optJSONObject("message")
                        ?.optString("content")?.takeIf(String::isNotBlank)
                        ?: throw JSONException("Missing message content")
                val parsed =
                    parseFacts(
                        normalizeJsonContent(content),
                        input.turns.sortedBy(EligibleConversationTurn::ordinal).map(EligibleConversationTurn::id),
                    )
                report("memory.extraction.response_parsed", "facts=${parsed.facts.size}")
                MemoryExtractionOutcome.Success(parsed)
            } catch (error: Exception) {
                // Exception messages for JSON/field validation are internal and never include the
                // provider body, so the class name is enough to distinguish the failure stage.
                report("memory.extraction.response_invalid", "reason=${error::class.java.simpleName}")
                MemoryExtractionOutcome.Failure("Memory-extraction response failed validation.")
            }
        }
    }

    /**
     * Models frequently wrap JSON in Markdown fences or add a one-line preamble despite the
     * system prompt. Strip only the envelope; field and source-turn validation stays strict.
     */
    private fun normalizeJsonContent(content: String): String {
        val trimmed = stripThinkingBlocks(content).trim()
        val unfenced =
            if (trimmed.startsWith("```")) {
                trimmed.replaceFirst(Regex("^```[A-Za-z0-9_-]*\\s*"), "")
                    .substringBeforeLast("```")
                    .trim()
            } else {
                trimmed
            }
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        return if (start >= 0 && end > start) unfenced.substring(start, end + 1) else unfenced
    }

    private fun readBoundedBody(response: Response): String? {
        val body = response.body
        if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) return null
        val buffer = okio.Buffer()
        val source = body.source()
        while (buffer.size < MAX_RESPONSE_BYTES + 1) {
            val remainingWithOverflowByte = MAX_RESPONSE_BYTES + 1 - buffer.size
            if (source.read(buffer, minOf(RESPONSE_READ_CHUNK_BYTES, remainingWithOverflowByte)) == -1L) break
        }
        return if (buffer.size > MAX_RESPONSE_BYTES) null else buffer.readUtf8().takeIf(String::isNotBlank)
    }

    private fun parseFacts(
        content: String,
        turnIdsInOrder: List<String>,
    ): MemoryExtractionResult {
        require(content.length <= MAX_RESPONSE_BYTES) { "Memory response is too large." }
        val factsJson =
            JSONObject(content).optJSONArray("facts")
                ?: throw JSONException("Missing facts array")
        require(factsJson.length() <= MAX_FACTS) { "Too many extracted facts." }
        val facts =
            (0 until factsJson.length()).mapNotNull { index ->
                val fact = factsJson.optJSONObject(index) ?: return@mapNotNull null
                val subject = fact.optString("subject").trim()
                val predicate = fact.optString("predicate").trim()
                val value = fact.optString("value").trim()
                val sourceTurnId = resolveSourceTurnId(fact.optString("source_turn_id").trim(), turnIdsInOrder)
                // A single hallucinated source turn must not discard every valid fact in the batch.
                if (subject.isBlank() || subject.length > MAX_FIELD_CHARS) return@mapNotNull null
                if (predicate.isBlank() || predicate.length > MAX_FIELD_CHARS) return@mapNotNull null
                if (value.isBlank() || value.length > MAX_FIELD_CHARS) return@mapNotNull null
                if (sourceTurnId == null) return@mapNotNull null
                ExtractedFactDraft(subject, predicate, value, sourceTurnId)
            }
        require(facts.isNotEmpty() || factsJson.length() == 0) { "No valid facts in the response." }
        return MemoryExtractionResult(facts)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SHARED_HTTP_CLIENT = OkHttpClient()
        private const val MEMORY_EXTRACTION_TEMPERATURE = 0.1
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private const val RESPONSE_READ_CHUNK_BYTES = 8_192L
        private const val MAX_RESPONSE_BYTES = 256 * 1024L
        private const val MAX_FACTS = 100
        private const val MAX_FIELD_CHARS = 2_000
        private const val MAX_ERROR_BODY_BYTES = 8_192L
        private const val ERROR_BODY_TIMEOUT_MILLIS = 1_000L
        private val SAFE_ERROR_CODES =
            setOf(
                "context_length_exceeded", "invalid_request_error", "authentication_error",
                "invalid_api_key", "permission_error", "permission_denied", "not_found_error",
                "model_not_found", "rate_limit_exceeded", "rate_limit_error", "insufficient_quota",
                "server_error", "api_error", "overloaded_error", "content_filter", "content_policy_violation",
            )
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 60L

        /**
         * Non-streamed completions return nothing until generation finishes. OkHttp's default
         * 10-second read timeout could abort slow responses; it does not bound the entire call.
         */
        internal const val DEFAULT_READ_TIMEOUT_MILLIS = 120_000L
    }
}
