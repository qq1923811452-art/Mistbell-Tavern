package com.mistbell.tavern.android.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.8,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    // S1 采样细项：null 时配合 explicitNulls=false 不出现在请求体
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    val stream: Boolean = false,
    // 思考模式开关（DeepSeek OpenAI 格式）：null 时不发送该字段，保持对任意网关的默认行为
    val thinking: ThinkingConfig? = null,
    // 流式用量开关：stream=true 且网关支持时带上，最后一片返回真实 usage
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

/** DeepSeek 思考模式开关：type = "enabled" / "disabled" */
@Serializable
data class ThinkingConfig(
    val type: String,
)

/**
 * OpenAI 兼容流式用量开关。include_usage=true 时网关会在流末尾追加一片仅含 usage 的 chunk，
 * 这是拿到"真实用量 + 前缀缓存命中统计"的唯一途径（开启它不影响正文增量）。
 */
@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = false,
)

/** 流式响应中的一帧，正文与思维链分开，避免把 reasoning 当成可见正文。 */
data class ChatDelta(
    val text: String = "",
    val thinking: String? = null,
    // 流结束时的最终用量（仅在最后一帧携带；此前各帧为 null）。
    // 供上层落库/展示"真实 API 用量"，不再只写日志。
    val usage: Usage? = null,
)

/**
 * 用量统计（OpenAI 兼容响应 usage）。DeepSeek 另附 prompt_cache_hit_tokens /
 * prompt_cache_miss_tokens，用于观测前缀缓存命中率——忽略未知键的 Json 会保持默认值 0。
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val cacheMissTokens: Int = 0,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
) {
    /** Map OpenAI-style cached_tokens to the same counters used by DeepSeek's usage fields. */
    fun withNormalizedCacheMetrics(): Usage {
        if (cacheHitTokens > 0 || cacheMissTokens > 0) return this
        val cachedTokens = promptTokensDetails?.cachedTokens ?: return this
        return copy(
            cacheHitTokens = cachedTokens,
            cacheMissTokens = (promptTokens - cachedTokens).coerceAtLeast(0),
        )
    }
}

@Serializable
data class PromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
) {
    @Serializable
    data class Choice(
        val message: ChatChoiceMessage? = null,
        val delta: ChatChoiceMessage? = null,
        // 截断诊断：finish_reason = "length" 表示响应被 max_tokens 砍断
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class ChatChoiceMessage(
        val role: String = "",
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        val reasoning: String? = null,
        val thinking: String? = null,
    )
}

// F1: SSE 流式 chunk 模型
@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    // DeepSeek 在最后一个 chunk 附 usage（含缓存命中统计），网关不发则保持 null
    val usage: Usage? = null,
)

@Serializable
data class ChunkChoice(
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    val thinking: String? = null,
)

/**
 * SSE data 行解析器（纯函数，供单测）。
 * 规则: "[DONE]"→null; 坏 JSON→null; choices 空→null; delta.content 空白→null; 否则返回 content。
 */
object SseParser {
    fun contentDelta(dataLine: String): String? = parseChunk(dataLine)?.let { contentOf(it) }

    /** 解析 chunk 原文；"[DONE]" 与坏 JSON 返回 null */
    fun parseChunk(dataLine: String): ChatCompletionChunk? {
        val trimmed = dataLine.trim()
        if (trimmed == "[DONE]") return null
        return try {
            json.decodeFromString(ChatCompletionChunk.serializer(), trimmed)
        } catch (_: Exception) {
            return null // 坏 JSON
        }
    }

    /** 从已解析 chunk 取正文增量（与 [contentDelta] 同规则，供复用已解码对象的调用方） */
    fun contentOf(chunk: ChatCompletionChunk): String? {
        val choice = chunk.choices.firstOrNull() ?: return null
        val content = choice.delta?.content ?: return null
        if (content.isBlank()) return null
        return content
    }

    /** OpenAI/DeepSeek 兼容网关的思维链增量字段。 */
    fun thinkingOf(chunk: ChatCompletionChunk): String? {
        val delta = chunk.choices.firstOrNull()?.delta ?: return null
        return listOfNotNull(delta.reasoningContent, delta.reasoning, delta.thinking)
            .firstOrNull { it.isNotBlank() }
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
}

object LlmClient {
    private val client =
        OkHttpClient.Builder()
            // API credentials must never be forwarded through an untrusted redirect.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private const val INITIAL_RETRY_DELAY_MS = 1000L

    /** S1: 按配置生成带 callTimeout 的派生客户端（钳制 15..600 秒）。 */
    private fun clientFor(config: LlmConfig): OkHttpClient =
        client.newBuilder()
            .callTimeout(config.timeoutSeconds.coerceIn(15, 600).toLong(), TimeUnit.SECONDS)
            .build()

    /** S1: 重试上限 = 1 + 配置重试次数（钳制 0..5）。 */
    private fun maxAttemptsFor(config: LlmConfig): Int = 1 + config.retries.coerceIn(0, 5)

    private const val LOG_TAG = "LlmUsage"
    private const val PERCENT_MULTIPLIER = 100.0

    /**
     * Merge usage fields from stream frames without counting repeated cumulative values twice.
     * Max-per-field also preserves values when a later protocol frame omits an earlier field.
     */
    private fun mergeUsage(
        current: Usage?,
        incoming: Usage?,
    ): Usage? =
        when {
            current == null -> incoming
            incoming == null -> current
            else -> {
                val promptTokens = maxOf(current.promptTokens, incoming.promptTokens)
                val completionTokens = maxOf(current.completionTokens, incoming.completionTokens)
                // Some protocols (notably Anthropic message_delta) omit input/total fields
                // on later cumulative frames. Preserve known totals and derive a minimum
                // consistent total from the best known prompt and completion counts.
                val totalTokens =
                    maxOf(
                        current.totalTokens,
                        incoming.totalTokens,
                        promptTokens + completionTokens,
                    )
                Usage(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    cacheHitTokens = maxOf(current.cacheHitTokens, incoming.cacheHitTokens),
                    cacheMissTokens = maxOf(current.cacheMissTokens, incoming.cacheMissTokens),
                    promptTokensDetails = incoming.promptTokensDetails ?: current.promptTokensDetails,
                )
            }
        }

    /**
     * 用量/前缀缓存命中日志（统一 LlmUsage 标签：`adb logcat -s LlmUsage`）。
     *
     * 前缀缓存只认同「从第 0 条消息起逐字节相同」的前缀——命中率低说明提示词头部有易变内容在漂移，
     * 这是排查"为什么比酒馆更消耗"的直接证据。网关不返回 usage 时也打一行，避免无从判断。
     */
    private fun logUsage(
        usage: Usage?,
        model: String,
        path: String,
    ) {
        if (usage == null) {
            android.util.Log.i(LOG_TAG, "[$path] usage 未返回（网关不提供用量统计）model=$model")
            return
        }
        val hit = usage.cacheHitTokens
        val miss = usage.cacheMissTokens
        // Use Long for the denominator to avoid Int overflow, and retain one decimal place
        // so low but non-zero cache hit rates are not rounded down to a misleading 0%.
        val cacheTokens = hit.toLong() + miss.toLong()
        val rate =
            if (cacheTokens > 0L) {
                String.format(Locale.US, "%.1f%%", hit.toDouble() * PERCENT_MULTIPLIER / cacheTokens)
            } else {
                "n/a"
            }
        android.util.Log.i(
            LOG_TAG,
            "[$path] prompt=${usage.promptTokens} 命中=$hit 未命中=$miss 命中率=$rate " +
                "completion=${usage.completionTokens} model=$model",
        )
    }

    /**
     * 非流式对话入口。
     *
     * 若请求携带思考模式开关（`disableThinking`）且被网关以参数错误拒绝，自动回退为**不发送该参数**
     * 重试一次——思考开关是 DeepSeek 系参数，OpenAI 兼容网关未必认（400 Unrecognized request argument），
     * 不回退的话一次参数差异就会打死整轮调用。
     */
    suspend fun chat(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): String =
        try {
            chatWithRetries(config, messages)
        } catch (e: Exception) {
            if (config.disableThinking && isThinkingParamRejected(e)) {
                android.util.Log.w("LlmClient", "thinking 参数被网关拒绝，回退为不发送后重试: ${e.message}")
                chatWithRetries(config.copy(disableThinking = false), messages)
            } else {
                throw e
            }
        }

    /** 疑似"思考参数被拒"：错误正文提到 thinking，或 HTTP 400（请求格式错误） */
    private fun isThinkingParamRejected(e: Exception): Boolean {
        val message = e.message.orEmpty()
        val mentionsThinking = message.contains("thinking", ignoreCase = true)
        if (!mentionsThinking) return false

        // A generic HTTP 400 can mean malformed JSON, invalid model, or another request
        // problem. Only fall back when the response looks like a parameter/field rejection.
        val parameterRejectionTerms =
            listOf(
                "unrecognized",
                "unknown parameter",
                "unsupported parameter",
                "invalid parameter",
                "not allowed",
                "unexpected field",
                "unknown field",
                "extra inputs",
                "not supported",
            )
        val explicitlyRejected = parameterRejectionTerms.any { message.contains(it, ignoreCase = true) }
        val isBadRequest = Regex("""\b400\b""").containsMatchIn(message)
        val mentionsRequestField =
            listOf("parameter", "argument", "field").any { message.contains(it, ignoreCase = true) }

        return explicitlyRejected || (isBadRequest && mentionsRequestField)
    }

    private suspend fun chatWithRetries(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): String {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            val maxAttempts = maxAttemptsFor(config)
            repeat(maxAttempts) { attempt ->
                try {
                    return@withContext executeChatRequest(config, messages)
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    if (attempt < maxAttempts - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) // 指数退避：1s, 2s, 4s
                        android.util.Log.w("LlmClient", "Request timeout, retry ${attempt + 1}/$maxAttempts after ${delayMs}ms")
                        delay(delayMs)
                    }
                } catch (e: IOException) {
                    lastException = e
                    // 网络错误可重试
                    if (attempt < maxAttempts - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                        android.util.Log.w("LlmClient", "Network error, retry ${attempt + 1}/$maxAttempts after ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    }
                } catch (e: Exception) {
                    // 其他错误（如 4xx 客户端错误）不重试
                    if (e.message?.contains("429") == true) {
                        // 速率限制，可以重试
                        lastException = e
                        if (attempt < maxAttempts - 1) {
                            val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) * 2 // 速率限制时延迟更久
                            android.util.Log.w("LlmClient", "Rate limit hit, retry ${attempt + 1}/$maxAttempts after ${delayMs}ms")
                            delay(delayMs)
                        }
                    } else {
                        // 其他错误直接抛出
                        throw e
                    }
                }
            }
            // 所有重试都失败
            throw lastException ?: Exception("LLM request failed after $maxAttempts retries")
        }
    }

    // internal 仅为单元测试开放：思考模式字段是否按需出现在请求体需要回归测试
    internal fun buildChatRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request = chatProtocolFor(config.type).buildRequest(config, messages, stream)

    private fun executeChatRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): String = executeChatContentRequest(config, messages).text

    private fun executeChatContentRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): ProtocolContent {
        val request = buildChatRequest(config, messages, stream = false)
        val response = clientFor(config).newCall(request).execute()
        if (!response.isSuccessful) {
            val statusCode = response.code
            response.close()
            // Never surface untrusted gateway error bodies, which may echo prompts or credentials.
            error("LLM API returned HTTP $statusCode")
        }
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val content = chatProtocolFor(config.type).parseContent(responseBody)
        logUsage(content.usage, config.model, "non-stream")
        // 截断是最隐蔽的失败：JSON 写到一半被 max_tokens 砍断，调用方只看到"解析失败/空结果"
        if (content.finishReason.equals("length", ignoreCase = true) ||
            content.finishReason.equals("max_tokens", ignoreCase = true)
        ) {
            android.util.Log.w(
                "LlmClient",
                "Response truncated by max_tokens (finishReason=${content.finishReason}, model=${config.model})",
            )
        }
        return content
    }

    /** 非流式响应的正文与思维链，供聊天落库使用；普通 [chat] 保持只返回正文的兼容接口。 */
    suspend fun chatWithThinking(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): ProtocolContent =
        try {
            chatContentWithRetries(config, messages)
        } catch (e: Exception) {
            if (config.disableThinking && isThinkingParamRejected(e)) {
                android.util.Log.w("LlmClient", "thinking 参数被网关拒绝，回退为不发送后重试: ${e.message}")
                chatContentWithRetries(config.copy(disableThinking = false), messages)
            } else {
                throw e
            }
        }

    private suspend fun chatContentWithRetries(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): ProtocolContent =
        withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            val maxAttempts = maxAttemptsFor(config)
            repeat(maxAttempts) { attempt ->
                try {
                    return@withContext executeChatContentRequest(config, messages)
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    if (attempt < maxAttempts - 1) delay(INITIAL_RETRY_DELAY_MS * (1 shl attempt))
                } catch (e: IOException) {
                    lastException = e
                    if (attempt < maxAttempts - 1) delay(INITIAL_RETRY_DELAY_MS * (1 shl attempt))
                } catch (e: Exception) {
                    if (e.message?.contains("429") == true && attempt < maxAttempts - 1) {
                        lastException = e
                        delay(INITIAL_RETRY_DELAY_MS * (1 shl attempt) * 2)
                    } else {
                        throw e
                    }
                }
            }
            throw lastException ?: Exception("LLM request failed after $maxAttempts retries")
        }

    /**
     * F1: SSE 真流式入口。冷流，每次发射一个 content 增量。
     * 首 token 前失败按退避策略重试（≤2 次: 1s/2s，429 翻倍）；已发出增量后不再重试。
     */
    fun chatStream(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): Flow<String> =
        chatStreamWithThinking(config, messages)
            .map { it.text }
            // 流末尾会附带一帧"仅用量"的 delta（text 为空）：只取正文的接口过滤掉它
            .filter { it.isNotEmpty() }

    /** 流式正文接口的增强版，保留思维链增量（最后一帧可能只带 [ChatDelta.usage]）。 */
    fun chatStreamWithThinking(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): Flow<ChatDelta> =
        flow {
            val maxAttempts = maxAttemptsFor(config) // S1: 复用配置重试上限（流式）
            for (attempt in 0 until maxAttempts) {
                var emittedAny = false
                try {
                    chatStreamOnce(config, messages).collect { delta ->
                        emittedAny = true
                        emit(delta)
                    }
                    return@flow
                } catch (e: CancellationException) {
                    throw e // 取消不重试
                } catch (e: Exception) {
                    // 已发出增量后不再重试
                    if (emittedAny) throw e
                    val retryable = e is IOException || e.message?.contains("429") == true
                    if (attempt < maxAttempts - 1 && retryable) {
                        val delayMs = 1000L * (1 shl attempt) * (if (e.message?.contains("429") == true) 2 else 1)
                        android.util.Log.w(
                            "LlmClient",
                            "Stream failed before first token, retry ${attempt + 1}/$maxAttempts after ${delayMs}ms: ${e.message}",
                        )
                        delay(delayMs)
                    } else {
                        throw e
                    }
                }
            }
        }

    /** 单次流式连接（callbackFlow + EventSources 桥接，awaitClose 取消双保险）。 */
    private fun chatStreamOnce(
        config: LlmConfig,
        messages: List<ChatMessage>,
    ): Flow<ChatDelta> =
        callbackFlow {
            val request = buildChatRequest(config, messages, stream = true)
            val protocol = chatProtocolFor(config.type)
            // 逐帧合并的真实用量；流结束时若仍为 null，则如实记录"网关未提供用量统计"
            var mergedUsage: Usage? = null
            val listener =
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        when (val result = protocol.parseStreamData(data)) {
                            is StreamParseResult.Delta -> {
                                mergedUsage = mergeUsage(mergedUsage, result.usage)
                                trySend(ChatDelta(result.text, result.thinking))
                            }
                            is StreamParseResult.UsageUpdate ->
                                mergedUsage = mergeUsage(mergedUsage, result.usage)
                            StreamParseResult.Ignore -> Unit
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (mergedUsage != null) {
                            logUsage(mergedUsage, config.model, "stream")
                            // 把合并后的真实用量随最后一帧交给上层（落库/展示），不再只写日志
                            trySend(ChatDelta(text = "", thinking = null, usage = mergedUsage))
                        } else {
                            logUsage(null, config.model, "stream")
                        }
                        close() // 服务端正常结束
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: okhttp3.Response?,
                    ) {
                        val message = t?.message ?: ""
                        // 某些兼容网关流式响应 Content-Type 非 text/event-stream 会被 okhttp-sse 拒收
                        if (message.contains("Content-Type", ignoreCase = true) || message.contains("content type", ignoreCase = true)) {
                            close(IllegalStateException("流式响应 Content-Type 不受支持（$message）：该网关可能不兼容 SSE 流式，请在模型设置中关闭流式输出（降级为普通请求）。"))
                            return
                        }
                        if (response != null) {
                            val statusCode = response.code
                            response.close()
                            close(IllegalStateException("LLM API returned HTTP $statusCode"))
                        } else {
                            close(IOException("LLM stream failed: ${t?.message}", t))
                        }
                    }
                }
            val es = EventSources.createFactory(clientFor(config)).newEventSource(request, listener)
            // 取消双保险: 收集方取消时同时断开 SSE（eventSource.cancel() 内部会取消底层 call）
            awaitClose { es.cancel() }
        }.buffer(Channel.UNLIMITED)

    suspend fun testConnection(config: LlmConfig): Boolean {
        return try {
            val testMessages = listOf(ChatMessage(role = "user", content = "Hi"))
            chat(config.copy(maxTokens = 5), testMessages)
            true
        } catch (_: Exception) {
            false
        }
    }
}
