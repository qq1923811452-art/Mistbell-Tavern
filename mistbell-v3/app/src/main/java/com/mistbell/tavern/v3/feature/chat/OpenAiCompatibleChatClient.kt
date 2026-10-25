package com.mistbell.tavern.v3.feature.chat

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
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

fun interface CredentialResolver {
    /** Resolve an opaque encrypted-store handle. Never log or persist the returned secret here. */
    fun resolve(handle: String): String?
}

enum class ChatFailureKind {
    NETWORK,
    HTTP_STATUS,
    INVALID_STREAM,
    CANCELLED,
}

data class ChatStreamFailure(
    val kind: ChatFailureKind,
    val httpStatus: Int? = null,
    val safeMessage: String,
)

data class ChatTokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedPromptTokens: Int? = null,
    /** Reasoning tokens reported by the provider; they are counted inside completion tokens. */
    val reasoningTokens: Int? = null,
)

data class ChatGenerationOptions(
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val includeUsage: Boolean = true,
    /** When false the request uses the non-streaming JSON endpoint. */
    val stream: Boolean = true,
    /** Enables provider-specific thinking/reasoning parameters. */
    val thinkingEnabled: Boolean = false,
) {
    init {
        require(temperature.isFinite() && temperature in 0.0..2.0) {
            "Temperature must be finite and between 0.0 and 2.0."
        }
        require(maxTokens in 1..200_000) { "maxTokens must be between 1 and 200000." }
    }
}

/** Why the provider stopped sending tokens. */
enum class ChatFinishReason { STOP, LENGTH, OTHER, EOF }

interface ChatStreamObserver {
    fun onDelta(text: String)
    fun onComplete()
    fun onFailure(failure: ChatStreamFailure)
    fun onUsage(usage: ChatTokenUsage) = Unit
    /** Reports the provider's finish reason before [onComplete]. */
    fun onFinish(reason: ChatFinishReason) = Unit
    /**
     * Reports hidden reasoning text for providers that stream it separately. It is never shown as
     * reply content; callers may only use it for diagnostics or to explain token usage.
     */
    fun onReasoningDelta(text: String) = Unit
}

class ChatClientConfigurationException(message: String) : IllegalStateException(message)

/**
 * OpenAI-compatible streaming client. It resolves only the explicitly selected CHAT profile,
 * disables redirects to prevent forwarding bearer credentials to another origin, and never
 * includes response bodies or credentials in error messages.
 *
 * Callbacks execute on OkHttp's dispatcher thread. The caller owns UI-thread dispatching and
 * cancellation; the returned Call can be cancelled when the user stops generation.
 */
class OpenAiCompatibleChatClient(
    private val providers: ProviderProfileSet,
    private val credentials: CredentialResolver,
    client: OkHttpClient = OkHttpClient(),
) {
    private val httpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // Some providers spend longer than OkHttp's default read timeout thinking before a token.
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun stream(
        messages: List<PromptMessage>,
        observer: ChatStreamObserver,
        options: ChatGenerationOptions = ChatGenerationOptions(),
    ): Call {
        require(messages.isNotEmpty()) { "At least one prompt message is required." }
        val profile = providers.profileFor(ProviderKind.CHAT)
            ?: throw ChatClientConfigurationException("No enabled chat provider is explicitly selected.")
        val validation = EndpointPolicy.validateBaseUrl(profile.baseUrl)
        val baseUrl = validation.normalizedBaseUrl
            ?: throw ChatClientConfigurationException(
                validation.reason ?: "Chat provider base URL is invalid.",
            )
        if (profile.modelId.isBlank()) {
            throw ChatClientConfigurationException("Chat model ID is required.")
        }

        val uri = try {
            URI(baseUrl)
        } catch (_: Exception) {
            throw ChatClientConfigurationException("Chat provider base URL is invalid.")
        }
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT)
        val localHttp = uri.scheme.equals("http", ignoreCase = true) &&
            host != null && host in LOOPBACK_HOSTS

        val credentialHandle = profile.credentialHandle?.takeIf(String::isNotBlank)
        val apiKey = if (credentialHandle != null) {
            credentials.resolve(credentialHandle)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw ChatClientConfigurationException("The selected chat credential is unavailable.")
        } else {
            null
        }
        if (apiKey == null && !localHttp) {
            throw ChatClientConfigurationException("A credential is required for a remote chat provider.")
        }

        val messagesJson = JSONArray()
        messages.forEach { message ->
            val role = when (message.role) {
                PromptRole.SYSTEM -> "system"
                PromptRole.USER -> "user"
                PromptRole.ASSISTANT -> "assistant"
            }
            messagesJson.put(
                JSONObject()
                    .put("role", role)
                    .put("content", message.content),
            )
        }
        val body = JSONObject()
            .put("model", profile.modelId)
            .put("stream", options.stream)
            .put("temperature", options.temperature)
            .put("max_tokens", options.maxTokens)
            .put("messages", messagesJson)
            .also { payload ->
                if (options.includeUsage && options.stream) {
                    payload.put("stream_options", JSONObject().put("include_usage", true))
                }
                val thinkingTemplate = if (options.thinkingEnabled) {
                    profile.thinkingBodyTemplate
                } else {
                    profile.thinkingDisabledBody
                }
                thinkingTemplate?.let { template ->
                    runCatching { JSONObject(template) }.getOrNull()?.let { extra ->
                        extra.keys().forEach { key ->
                            if (key !in CONTROLLED_REQUEST_KEYS) payload.put(key, extra.opt(key))
                        }
                    }
                }
            }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Accept", if (options.stream) "text/event-stream" else "application/json")
            .header("Content-Type", "application/json")
            .post(body)
        if (apiKey != null) requestBuilder.header("Authorization", "Bearer $apiKey")

        val call = httpClient.newCall(requestBuilder.build())
        if (!options.stream) {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    observer.onFailure(
                        if (call.isCanceled()) {
                            ChatStreamFailure(ChatFailureKind.CANCELLED, safeMessage = "Generation was cancelled.")
                        } else {
                            ChatStreamFailure(ChatFailureKind.NETWORK, safeMessage = "Chat provider request failed.")
                        },
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { opened ->
                        if (!opened.isSuccessful) {
                            observer.onFailure(
                                ChatStreamFailure(
                                    kind = ChatFailureKind.HTTP_STATUS,
                                    httpStatus = opened.code,
                                    safeMessage = "Chat provider returned HTTP ${opened.code}.",
                                ),
                            )
                            return
                        }
                        val payload = runCatching {
                            val body = opened.body ?: return@runCatching null
                            require(body.contentLength() <= MAX_RESPONSE_BYTES || body.contentLength() == -1L) {
                                "Chat response exceeds the size limit."
                            }
                            readLimitedUtf8(body.source(), MAX_RESPONSE_BYTES)
                        }.getOrNull()
                        parseCompletionPayload(payload, opened.header("Content-Type"), observer)
                    }
                }
            })
            return call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                observer.onFailure(
                    if (call.isCanceled()) {
                        ChatStreamFailure(ChatFailureKind.CANCELLED, safeMessage = "Generation was cancelled.")
                    } else {
                        ChatStreamFailure(ChatFailureKind.NETWORK, safeMessage = "Chat provider request failed.")
                    },
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { opened ->
                    if (!opened.isSuccessful) {
                        observer.onFailure(
                            ChatStreamFailure(
                                kind = ChatFailureKind.HTTP_STATUS,
                                httpStatus = opened.code,
                                safeMessage = "Chat provider returned HTTP ${opened.code}.",
                            ),
                        )
                        return
                    }
                    val responseBody = opened.body
                    if (responseBody == null) {
                        observer.onFailure(
                            ChatStreamFailure(
                                ChatFailureKind.INVALID_STREAM,
                                safeMessage = "Chat provider returned an empty response.",
                            ),
                        )
                        return
                    }
                    try {
                        val source = responseBody.source()
                        var sawDone = false
                        var sawSseEvent = false
                        val nonSseBody = StringBuilder()
                        var finishReason: String? = null
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            require(line.length <= MAX_SSE_LINE_CHARS) {
                                "Streaming event exceeds the line limit."
                            }
                            if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) {
                                if (line.trimStart().startsWith("{")) nonSseBody.appendLine(line)
                                continue
                            }
                            sawSseEvent = true
                            val eventData = line.removePrefix("data:").trimStart()
                            if (eventData == "[DONE]") {
                                sawDone = true
                                break
                            }
                            val event = try {
                                JSONObject(eventData)
                            } catch (_: JSONException) {
                                observer.onFailure(
                                    ChatStreamFailure(
                                        ChatFailureKind.INVALID_STREAM,
                                        safeMessage = "Chat provider returned malformed streaming data.",
                                    ),
                                )
                                return
                            }
                            if (options.includeUsage) {
                                event.optJSONObject("usage")?.let { usage ->
                                    val promptTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0))
                                    val completionTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0))
                                    val totalTokens = usage.optInt("total_tokens", promptTokens + completionTokens)
                                    val details = usage.optJSONObject("prompt_tokens_details")
                                    val cachedTokens = details?.let {
                                        when {
                                            it.has("cached_tokens") -> it.optInt("cached_tokens")
                                            it.has("cache_read_input_tokens") -> it.optInt("cache_read_input_tokens")
                                            else -> null
                                        }
                                    } ?: when {
                                        usage.has("prompt_cache_hit_tokens") ->
                                            usage.optInt("prompt_cache_hit_tokens")
                                        usage.has("total_cached_tokens") ->
                                            usage.optInt("total_cached_tokens")
                                        else -> null
                                    }
                                    val reasoning = usage.optJSONObject("completion_tokens_details")
                                        ?.takeIf { it.has("reasoning_tokens") }
                                        ?.optInt("reasoning_tokens", 0)
                                        ?.coerceAtLeast(0)
                                    observer.onUsage(
                                        ChatTokenUsage(
                                            promptTokens = promptTokens.coerceAtLeast(0),
                                            completionTokens = completionTokens.coerceAtLeast(0),
                                            totalTokens = totalTokens.coerceAtLeast(0),
                                            cachedPromptTokens = cachedTokens?.coerceAtLeast(0),
                                            reasoningTokens = reasoning,
                                        ),
                                    )
                                }
                            }
                            providerErrorMessage(event)?.let { message ->
                                observer.onFailure(
                                    ChatStreamFailure(ChatFailureKind.INVALID_STREAM, safeMessage = message),
                                )
                                return
                            }
                            val choices = event.optJSONArray("choices") ?: continue
                            val choice = choices.optJSONObject(0) ?: continue
                            choice.optString("finish_reason")
                                .takeIf { it.isNotBlank() && it != "null" }
                                ?.let { finishReason = it }
                            val delta = choice.optJSONObject("delta") ?: continue
                            textOf(delta.opt("content"))
                                ?.takeIf(String::isNotEmpty)
                                ?.let(observer::onDelta)
                            textOf(delta.opt("reasoning_content"))
                                ?.takeIf(String::isNotEmpty)
                                ?.let(observer::onReasoningDelta)
                        }
                        if (!sawSseEvent && nonSseBody.isNotEmpty()) {
                            parseCompletionPayload(
                                nonSseBody.toString(),
                                opened.header("Content-Type"),
                                observer,
                            )
                            return
                        }
                        val reason = when {
                            finishReason.equals("length", ignoreCase = true) -> ChatFinishReason.LENGTH
                            finishReason.equals("stop", ignoreCase = true) ||
                                finishReason.equals("end_turn", ignoreCase = true) -> ChatFinishReason.STOP
                            finishReason != null -> ChatFinishReason.OTHER
                            sawDone -> ChatFinishReason.STOP
                            else -> ChatFinishReason.EOF
                        }
                        observer.onFinish(reason)
                        observer.onComplete()
                    } catch (_: IOException) {
                        observer.onFailure(
                            ChatStreamFailure(
                                kind = if (call.isCanceled()) ChatFailureKind.CANCELLED else ChatFailureKind.NETWORK,
                                safeMessage = if (call.isCanceled()) {
                                    "Generation was cancelled."
                                } else {
                                    "Chat stream was interrupted."
                                },
                            ),
                        )
                    }
                }
            }
        })
        return call
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

        /** Fields owned by the client; a provider template must never override them. */
        private val CONTROLLED_REQUEST_KEYS = setOf(
            "model", "messages", "stream", "stream_options",
            "temperature", "max_tokens", "n", "stop",
        )
        const val READ_TIMEOUT_SECONDS = 180L
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        const val MAX_SSE_LINE_CHARS = 1_000_000
        const val READ_CHUNK_BYTES = 8 * 1024
        private const val MAX_DIAGNOSTIC_KEYS = 6
        private const val MAX_ERROR_MESSAGE_CHARS = 300
    }

    private fun readLimitedUtf8(source: BufferedSource, maxBytes: Long): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = okio.Buffer()
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES.toLong())
            if (read < 0) break
            output.write(buffer.readByteArray(read))
            require(output.size() <= maxBytes) { "Chat response exceeds the size limit." }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * Reads completion text from the OpenAI shape plus the shapes some relays return for Gemini:
     * array content parts, choices[0].delta/text, or native candidates[0].content.parts[].
     */
    private fun completionText(root: JSONObject): String? {
        val first = root.optJSONArray("choices")?.optJSONObject(0)
        val candidateParts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val value = first?.optJSONObject("message")?.opt("content")
            ?: first?.optJSONObject("delta")?.opt("content")
            ?: first?.opt("text")
            ?: candidateParts
            ?: root.opt("content")
        return when (value) {
            null -> root.optString("text").takeIf(String::isNotEmpty)
            is String -> value
            else -> textOf(value)
        }
    }

    private fun reasoningText(root: JSONObject): String? {
        val first = root.optJSONArray("choices")?.optJSONObject(0)
        val value = first?.optJSONObject("message")?.opt("reasoning_content")
            ?: first?.optJSONObject("delta")?.opt("reasoning_content")
        return textOf(value)
    }

    private fun textOf(value: Any?): String? = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val part = value.opt(index)
                val text = if (part is JSONObject) part.optString("text") else part as? String
                if (!text.isNullOrEmpty()) append(text)
            }
        }.takeIf(String::isNotEmpty)
        is JSONObject -> value.optString("text").takeIf(String::isNotEmpty)
            ?: textOf(value.optJSONArray("parts"))
        else -> null
    }

    private fun providerErrorMessage(root: JSONObject?): String? {
        val error = root?.opt("error") ?: return null
        val message = when (error) {
            is JSONObject -> error.optString("message")
                .takeIf(String::isNotBlank)
                ?: error.optString("type").takeIf(String::isNotBlank)
            is String -> error.takeIf(String::isNotBlank)
            else -> null
        } ?: return "Chat provider returned an error without a message."
        return message.take(MAX_ERROR_MESSAGE_CHARS)
    }

    /**
     * Handles a complete JSON completion body. Used by the non-streaming request path and as a
     * fallback when a relay ignores `stream` and returns plain JSON to a streaming request.
     */
    private fun parseCompletionPayload(
        payload: String?,
        contentTypeHeader: String?,
        observer: ChatStreamObserver,
    ) {
        val root = runCatching { JSONObject(payload.orEmpty()) }.getOrNull()
        providerErrorMessage(root)?.let { message ->
            observer.onFailure(ChatStreamFailure(ChatFailureKind.INVALID_STREAM, safeMessage = message))
            return
        }
        val completion = root?.let(::completionText)
        if (root == null || completion == null) {
            observer.onFailure(
                ChatStreamFailure(
                    ChatFailureKind.INVALID_STREAM,
                    safeMessage = malformedCompletionMessage(contentTypeHeader, payload, root),
                ),
            )
            return
        }
        if (completion.isNotEmpty()) observer.onDelta(completion)
        root.let(::reasoningText)?.takeIf(String::isNotEmpty)?.let(observer::onReasoningDelta)
        root.optJSONObject("usage")?.let { usage -> emitUsage(usage, observer) }
        observer.onFinish(ChatFinishReason.STOP)
        observer.onComplete()
    }

    private fun emitUsage(usage: JSONObject, observer: ChatStreamObserver) {
        val promptTokens = usage.optInt("prompt_tokens", 0).coerceAtLeast(0)
        val completionTokens = usage.optInt("completion_tokens", 0).coerceAtLeast(0)
        val totalTokens = usage.optInt("total_tokens", 0).coerceAtLeast(promptTokens + completionTokens)
        val cached = usage.optJSONObject("prompt_tokens_details")
            ?.optInt("cached_tokens", 0)
            ?.coerceAtLeast(0)
        val reasoning = usage.optJSONObject("completion_tokens_details")
            ?.takeIf { it.has("reasoning_tokens") }
            ?.optInt("reasoning_tokens", 0)
            ?.coerceAtLeast(0)
        if (promptTokens > 0 || completionTokens > 0) {
            observer.onUsage(
                ChatTokenUsage(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    cachedPromptTokens = cached,
                    reasoningTokens = reasoning,
                ),
            )
        }
    }

    private fun malformedCompletionMessage(
        contentTypeHeader: String?,
        payload: String?,
        root: JSONObject?,
    ): String {
        val contentType = contentTypeHeader
            ?.substringBefore(';')
            ?.trim()
            .orEmpty()
            .ifEmpty { "unknown" }
        val bytes = payload?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val keys = root?.keys()
            ?.asSequence()
            ?.take(MAX_DIAGNOSTIC_KEYS)
            ?.joinToString(",")
            .orEmpty()
            .ifEmpty { "unparsed" }
        return "Chat provider returned malformed completion data " +
            "(type=$contentType; bytes=$bytes; keys=$keys)."
    }

}
