package com.mistbell.tavern.android.data.api

import okhttp3.Request

/**
 * 对话协议适配：把统一的 [ChatMessage] 列表编码为目标协议的请求，
 * 并把目标协议的响应 / SSE 流解码回统一形态。
 *
 * 与协议无关的管道（重试、取消双保险、超时、用量日志）留在 LlmClient 共享；
 * 每个协议只实现四处差异：请求体/认证、响应解析、SSE 增量解析。
 */
internal interface ChatProtocol {
    fun buildRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request

    /** 解析非流式响应体为统一正文 + 用量 + 结束原因（截断诊断用） */
    fun parseContent(body: String): ProtocolContent

    /** 解析一条 SSE data 负载：正文增量 / 用量更新 / 忽略 */
    fun parseStreamData(data: String): StreamParseResult
}

/** 非流式响应的统一形态 */
data class ProtocolContent(
    val text: String,
    val usage: Usage?,
    val finishReason: String?,
    /** 模型通过独立 reasoning/thinking 字段返回的思维链。 */
    val thinking: String? = null,
)

sealed class StreamParseResult {
    /**
     * 一帧可以同时带正文和思维链（部分网关会这样返回）。
     *
     * [usage] 为可选：少数网关会把用量与正文放在同一帧里返回；不带出即丢用量，
     * 而项目②要求"真实用量不得失真"，故在此透传（协议侧有则填、无则留 null）。
     */
    data class Delta(
        val text: String,
        val thinking: String? = null,
        val usage: Usage? = null,
    ) : StreamParseResult()

    data class UsageUpdate(val usage: Usage) : StreamParseResult()

    object Ignore : StreamParseResult()
}

/** 按接口类型选协议适配（custom 与 openai 同协议） */
internal fun chatProtocolFor(type: String): ChatProtocol =
    when (type) {
        ApiType.ANTHROPIC -> AnthropicChatProtocol
        ApiType.GEMINI -> GeminiChatProtocol
        else -> OpenAiChatProtocol
    }
