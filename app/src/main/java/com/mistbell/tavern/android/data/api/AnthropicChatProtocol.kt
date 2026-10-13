package com.mistbell.tavern.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages API 适配（https://api.anthropic.com/v1/messages）。
 *
 * 与 OpenAI 格式的差异：
 * - 认证 x-api-key + anthropic-version 头（非 Bearer）
 * - system 提示提取为顶级字段，messages 仅允许 user/assistant
 * - 无 frequency_penalty；思考参数（thinking 开关）不适用，忽略
 * - 响应正文在 content[] 数组；流式为 content_block_delta / message_delta 事件（无 [DONE]）
 */
internal object AnthropicChatProtocol : ChatProtocol {
    private const val ANTHROPIC_VERSION = "2023-06-01"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val messages: List<AnthropicMessage>,
        val stream: Boolean = false,
        val temperature: Double? = null,
        @SerialName("top_p") val topP: Double? = null,
        @SerialName("top_k") val topK: Int? = null,
    )

    @Serializable
    private data class AnthropicMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class AnthropicResponse(
        val content: List<ContentBlock> = emptyList(),
        val usage: AnthropicUsage? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
    ) {
        @Serializable
        data class ContentBlock(
            val type: String = "",
            val text: String = "",
            val thinking: String = "",
        )

        @Serializable
        data class AnthropicUsage(
            @SerialName("input_tokens") val inputTokens: Int = 0,
            @SerialName("output_tokens") val outputTokens: Int = 0,
            @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
            @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
        )
    }

    /** SSE data payloads: message_start nests usage under message; message_delta reports it at top level. */
    @Serializable
    private data class AnthropicEvent(
        val type: String = "",
        val delta: EventDelta? = null,
        val message: EventMessage? = null,
        val usage: AnthropicResponse.AnthropicUsage? = null,
    ) {
        @Serializable
        data class EventDelta(
            val type: String = "",
            val text: String = "",
            val thinking: String = "",
        )

        @Serializable
        data class EventMessage(
            val usage: AnthropicResponse.AnthropicUsage? = null,
        )
    }

    private fun normalizeUsage(usage: AnthropicResponse.AnthropicUsage): Usage {
        val totalPromptTokens =
            usage.inputTokens + usage.cacheCreationInputTokens + usage.cacheReadInputTokens
        return Usage(
            promptTokens = totalPromptTokens,
            completionTokens = usage.outputTokens,
            totalTokens = totalPromptTokens + usage.outputTokens,
            cacheHitTokens = usage.cacheReadInputTokens,
            cacheMissTokens = usage.inputTokens + usage.cacheCreationInputTokens,
        )
    }

    override fun buildRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request {
        // system 提取为顶级字段；其余消息保持 user/assistant（原样透传）
        val systemText = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.ifBlank { null }
        val chatMessages =
            messages
                .filter { it.role != "system" }
                .map { AnthropicMessage(role = it.role, content = it.content) }
        val requestBody =
            AnthropicRequest(
                model = config.model,
                maxTokens = config.maxTokens,
                system = systemText,
                messages = chatMessages,
                stream = stream,
                temperature = config.temperature,
                topP = config.topP,
                topK = config.topK,
            )
        val bodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
        val base = config.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        return Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
    }

    override fun parseContent(body: String): ProtocolContent {
        val response = json.decodeFromString(AnthropicResponse.serializer(), body)
        val text = response.content.filter { it.type == "text" }.joinToString("") { it.text }
        val thinking =
            response.content
                .filter { it.type == "thinking" || it.type == "redacted_thinking" }
                .joinToString("") { it.thinking.ifBlank { it.text } }
                .ifBlank { null }
        val usage = response.usage?.let(::normalizeUsage)
        // stop_reason=max_tokens 对应截断（同 OpenAI 的 finish_reason=length）
        return ProtocolContent(text = text, usage = usage, finishReason = response.stopReason, thinking = thinking)
    }

    override fun parseStreamData(data: String): StreamParseResult {
        val event = parseEvent(data) ?: return StreamParseResult.Ignore
        return when (event.type) {
            "content_block_delta" -> {
                val delta = event.delta
                val text = delta?.takeIf { it.type == "text_delta" }?.text.orEmpty()
                val thinking = delta?.takeIf { it.type == "thinking_delta" }?.thinking.orEmpty()
                if (text.isNotBlank() || thinking.isNotBlank()) {
                    StreamParseResult.Delta(text, thinking.ifBlank { null })
                } else {
                    StreamParseResult.Ignore
                }
            }
            // message_start 携带真实 input_tokens（提示词用量）
            "message_start" ->
                (event.message?.usage ?: event.usage)?.let {
                    StreamParseResult.UsageUpdate(normalizeUsage(it))
                } ?: StreamParseResult.Ignore
            // message_delta 只带累计 output_tokens，input_tokens 缺省为 0。
            // 这里把 totalTokens 也置 0（=本帧未提供），交给 LlmClient 用量合并器：
            // prompt 保留 message_start 的真实值，total 取 prompt+completion —— 不再覆盖成 0（修 P2-1）
            "message_delta" ->
                event.usage?.let {
                    StreamParseResult.UsageUpdate(
                        Usage(
                            promptTokens = 0,
                            completionTokens = it.outputTokens,
                            totalTokens = 0,
                        ),
                    )
                } ?: StreamParseResult.Ignore
            else -> StreamParseResult.Ignore
        }
    }

    /** 解析 SSE data 负载为事件对象；[DONE] 与坏 JSON 返回 null */
    private fun parseEvent(data: String): AnthropicEvent? {
        val trimmed = data.trim()
        if (trimmed == "[DONE]") return null
        return try {
            json.decodeFromString(AnthropicEvent.serializer(), trimmed)
        } catch (_: Exception) {
            null
        }
    }
}
