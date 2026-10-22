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

interface ChatStreamObserver {
    fun onDelta(text: String)
    fun onComplete()
    fun onFailure(failure: ChatStreamFailure)
    fun onUsage(usage: ChatTokenUsage) = Unit
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
        .readTimeout(0, TimeUnit.MILLISECONDS)
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
                if (options.thinkingEnabled) {
                    profile.thinkingBodyTemplate?.let { template ->
                        runCatching { JSONObject(template) }.getOrNull()?.let { extra ->
                            extra.keys().forEach { key -> payload.put(key, extra.opt(key)) }
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
                        val payload = runCatching { opened.body?.string() }.getOrNull()
                        val message = runCatching {
                            JSONObject(payload.orEmpty())
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("message")
                        }.getOrNull()
                        if (message == null) {
                            observer.onFailure(
                                ChatStreamFailure(
                                    ChatFailureKind.INVALID_STREAM,
                                    safeMessage = "Chat provider returned malformed completion data.",
                                ),
                            )
                            return
                        }
                        val content = message.optString("content")
                        if (content.isNotEmpty()) observer.onDelta(content)
                        observer.onComplete()
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
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) {
                                continue
                            }
                            val eventData = line.removePrefix("data:").trimStart()
                            if (eventData == "[DONE]") break
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
                                    observer.onUsage(
                                        ChatTokenUsage(
                                            promptTokens = promptTokens.coerceAtLeast(0),
                                            completionTokens = completionTokens.coerceAtLeast(0),
                                            totalTokens = totalTokens.coerceAtLeast(0),
                                            cachedPromptTokens = cachedTokens?.coerceAtLeast(0),
                                        ),
                                    )
                                }
                            }
                            val choices = event.optJSONArray("choices") ?: continue
                            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                            val content = delta.opt("content")
                            if (content is String && content.isNotEmpty()) observer.onDelta(content)
                        }
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
    }
}
