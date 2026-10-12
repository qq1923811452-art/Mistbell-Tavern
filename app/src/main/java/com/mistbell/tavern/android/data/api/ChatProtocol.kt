package com.mistbell.tavern.android.data.api

import okhttp3.Request

/**
 * 对话协议适配层。
 * 请求、响应解析、SSE 增量解析由协议实现负责。
 */
internal interface ChatProtocol {
    fun buildRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request

    fun parseContent(body: String): ProtocolContent

    fun parseStreamData(data: String): StreamParseResult

    val capabilities: ProtocolCapabilities
        get() = ProtocolCapabilities()
}

/** 协议能力声明 */
data class ProtocolCapabilities(
    val streaming: Boolean = true,
    val usage: Boolean = true,
    val thinking: Boolean = true,
)

/** 非流式响应统一结构 */
data class ProtocolContent(
    val text: String,
    val usage: Usage?,
    val finishReason: String?,
    val thinking: String? = null,
)

sealed class StreamParseResult {
    data class Delta(
        val text: String,
        val thinking: String? = null,
        val usage: Usage? = null,
    ) : StreamParseResult()

    data class UsageUpdate(val usage: Usage) : StreamParseResult()

    data object Ignore : StreamParseResult()
}

/** 按接口类型选择协议适配 */
internal fun chatProtocolFor(type: String): ChatProtocol =
    when (type.trim().lowercase()) {
        ApiType.ANTHROPIC.lowercase() -> AnthropicChatProtocol
        ApiType.GEMINI.lowercase() -> GeminiChatProtocol
        ApiType.OPENAI.lowercase(), ApiType.CUSTOM.lowercase() -> OpenAiChatProtocol
        else -> OpenAiChatProtocol
    }
