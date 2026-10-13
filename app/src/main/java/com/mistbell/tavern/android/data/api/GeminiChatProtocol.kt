package com.mistbell.tavern.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini generateContent 适配（generativelanguage.googleapis.com）。
 *
 * 与 OpenAI 格式的差异：
 * - API key 使用 x-goog-api-key 请求头，流式仅在 query 加 alt=sse
 * - 请求体 contents[]：assistant 角色映射为 "model"，system 提取为 systemInstruction
 * - 采样参数在 generationConfig（maxOutputTokens/temperature/topP/topK），无 frequency_penalty
 * - 响应 candidates[].content.parts[] 拼接；用量在 usageMetadata
 */
internal object GeminiChatProtocol : ChatProtocol {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null,
        @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig? = null,
    )

    @Serializable
    private data class GeminiContent(
        val role: String? = null,
        val parts: List<GeminiPart>,
    ) {
        @Serializable
        data class GeminiPart(
            val text: String = "",
            val thought: Boolean = false,
        )
    }

    @Serializable
    private data class GeminiGenerationConfig(
        @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
        val temperature: Double? = null,
        @SerialName("topP") val topP: Double? = null,
        @SerialName("topK") val topK: Int? = null,
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
        @SerialName("usageMetadata") val usageMetadata: GeminiUsage? = null,
    ) {
        @Serializable
        data class GeminiCandidate(
            val content: GeminiContent? = null,
            @SerialName("finishReason") val finishReason: String? = null,
        )

        @Serializable
        data class GeminiUsage(
            @SerialName("promptTokenCount") val promptTokens: Int = 0,
            @SerialName("candidatesTokenCount") val candidatesTokens: Int = 0,
            @SerialName("totalTokenCount") val totalTokens: Int = 0,
            @SerialName("cachedContentTokenCount") val cachedContentTokens: Int = 0,
        )
    }

    override fun buildRequest(
        config: LlmConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): Request {
        val systemText = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.ifBlank { null }
        val contents =
            messages
                .filter { it.role != "system" }
                .map {
                    // Gemini 角色只有 user/model：assistant → model
                    GeminiContent(
                        role = if (it.role == "assistant") "model" else "user",
                        parts = listOf(GeminiContent.GeminiPart(text = it.content)),
                    )
                }
        val requestBody =
            GeminiRequest(
                contents = contents,
                systemInstruction =
                    systemText?.let {
                        GeminiContent(parts = listOf(GeminiContent.GeminiPart(text = it)))
                    },
                generationConfig =
                    GeminiGenerationConfig(
                        maxOutputTokens = config.maxTokens,
                        temperature = config.temperature,
                        topP = config.topP,
                        topK = config.topK,
                    ),
            )
        val bodyJson = json.encodeToString(GeminiRequest.serializer(), requestBody)
        val base = config.baseUrl.trimEnd('/')
        val modelPath = "models/${config.model}:generateContent"
        val endpoint =
            if (base.endsWith("/v1beta")) {
                "$base/$modelPath"
            } else {
                "$base/v1beta/$modelPath"
            }
        val urlBuilder = endpoint.toHttpUrl().newBuilder()
        if (stream) urlBuilder.addQueryParameter("alt", "sse")
        return Request.Builder()
            .url(urlBuilder.build())
            .addHeader("x-goog-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
    }

    override fun parseContent(body: String): ProtocolContent {
        val response = json.decodeFromString(GeminiResponse.serializer(), body)
        val text =
            response.candidates
                .mapNotNull { it.content }
                .flatMap { it.parts.filterNot(GeminiContent.GeminiPart::thought) }
                .joinToString("") { it.text }
        val thinking =
            response.candidates
                .mapNotNull { it.content }
                .flatMap { it.parts.filter(GeminiContent.GeminiPart::thought) }
                .joinToString("") { it.text }
                .ifBlank { null }
        val usage =
            response.usageMetadata?.let {
                Usage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.candidatesTokens,
                    totalTokens = it.totalTokens,
                    cacheHitTokens = it.cachedContentTokens,
                    cacheMissTokens = (it.promptTokens - it.cachedContentTokens).coerceAtLeast(0),
                )
            }
        return ProtocolContent(
            text = text,
            usage = usage,
            finishReason = response.candidates.firstOrNull()?.finishReason,
            thinking = thinking,
        )
    }

    override fun parseStreamData(data: String): StreamParseResult {
        val response = parseResponse(data) ?: return StreamParseResult.Ignore
        val parts =
            response.candidates
                .mapNotNull { it.content }
                .flatMap { it.parts }
        val deltaText =
            parts
                .filterNot(GeminiContent.GeminiPart::thought)
                .joinToString("") { it.text }
                .takeIf { it.isNotBlank() }
        val deltaThinking =
            parts
                .filter(GeminiContent.GeminiPart::thought)
                .joinToString("") { it.text }
                .takeIf { it.isNotBlank() }
        val usage =
            response.usageMetadata?.let {
                Usage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.candidatesTokens,
                    totalTokens = it.totalTokens,
                )
            }
        val usageUpdate = usage?.let { StreamParseResult.UsageUpdate(it) }
        return if (deltaText != null || deltaThinking != null) {
            // 同帧可能既带正文又带 usageMetadata：一并交给上层合并，避免丢用量（P2-4）
            StreamParseResult.Delta(deltaText.orEmpty(), deltaThinking, usage)
        } else {
            usageUpdate ?: StreamParseResult.Ignore
        }
    }

    /** 解析 SSE data 负载为响应对象；[DONE] 与坏 JSON 返回 null */
    private fun parseResponse(data: String): GeminiResponse? {
        val trimmed = data.trim()
        if (trimmed == "[DONE]") return null
        return try {
            json.decodeFromString(GeminiResponse.serializer(), trimmed)
        } catch (_: Exception) {
            null
        }
    }
}
