package com.mistbell.tavern.android.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容协议（openai / custom 网关）：原 LlmClient 行为原样搬迁。
 */
internal object OpenAiChatProtocol : ChatProtocol {
    // S1: null 字段不出现在请求体
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    override fun buildRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request {
        val requestBody =
            ChatCompletionRequest(
                model = config.model,
                messages = messages,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                topP = config.topP,
                topK = config.topK,
                frequencyPenalty = config.frequencyPenalty,
                stream = stream,
                // disableThinking=true 才发思考开关；false 时为 null，配合 explicitNulls 不进请求体
                thinking = ThinkingConfig(type = "disabled").takeIf { config.disableThinking },
                // 流式时要求网关在最后一片返回 usage（真实用量 + 前缀缓存命中统计）；
                // 非流式不带该字段（stream=false → null，配合 explicitNulls 不出现在请求体）
                streamOptions = StreamOptions(includeUsage = true).takeIf { stream },
            )
        val bodyJson = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)
        val url = "${config.baseUrl.trimEnd('/')}/chat/completions"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
    }

    override fun parseContent(body: String): ProtocolContent {
        val completion = json.decodeFromString(ChatCompletionResponse.serializer(), body)
        val choice = completion.choices.firstOrNull()
        val message = choice?.message
        return ProtocolContent(
            text = message?.content ?: "",
            usage = completion.usage,
            finishReason = choice?.finishReason,
            thinking =
                listOfNotNull(message?.reasoningContent, message?.reasoning, message?.thinking)
                    .firstOrNull { it.isNotBlank() },
        )
    }

    override fun parseStreamData(data: String): StreamParseResult {
        val chunk = SseParser.parseChunk(data) ?: return StreamParseResult.Ignore
        val usageUpdate =
            chunk.usage?.let { StreamParseResult.UsageUpdate(it) }
        val deltaText = SseParser.contentOf(chunk)
        val deltaThinking = SseParser.thinkingOf(chunk)
        val delta =
            if (!deltaText.isNullOrBlank() || !deltaThinking.isNullOrBlank()) {
                // 同帧可能既带正文又带 usage：一并交给上层合并，避免整帧用量被丢（P2-4）
                StreamParseResult.Delta(deltaText.orEmpty(), deltaThinking, chunk.usage)
            } else {
                null
            }
        return delta ?: usageUpdate ?: StreamParseResult.Ignore
    }
}
