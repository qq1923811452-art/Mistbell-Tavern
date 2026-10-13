package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多协议适配单测：请求体编码、认证头/URL、响应解析、SSE 增量解析。
 *
 * ChatProtocol 各实现是 internal，测试同包可直接访问。
 */
class ChatProtocolTest {
    private val messages =
        listOf(
            ChatMessage(role = "system", content = "你是助手"),
            ChatMessage(role = "user", content = "你好"),
            ChatMessage(role = "assistant", content = "你好！有什么可以帮你？"),
            ChatMessage(role = "user", content = "讲个笑话"),
        )

    private fun anthropicConfig() =
        LlmConfig(
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
            model = "claude-sonnet-4-20250514",
            type = ApiType.ANTHROPIC,
        )

    private fun geminiConfig() =
        LlmConfig(
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "gemini-key",
            model = "gemini-2.0-flash",
            type = ApiType.GEMINI,
        )

    private fun bodyOf(request: okhttp3.Request): String =
        request.body
            ?.let { okio.Buffer().also { buf -> it.writeTo(buf) }.readUtf8() }
            .orEmpty()

    // ---- Anthropic 请求 ----

    @Test
    fun `anthropic 请求头与端点`() {
        val request = AnthropicChatProtocol.buildRequest(anthropicConfig(), messages, stream = false)

        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("sk-ant-test", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("application/json", request.header("Content-Type"))
    }

    @Test
    fun `anthropic system 提取为顶级字段且消息仅含 user assistant`() {
        val body = bodyOf(AnthropicChatProtocol.buildRequest(anthropicConfig(), messages, stream = false))

        assertTrue(body.contains("\"system\":\"你是助手\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"role\":\"assistant\""))
        // system 角色不得出现在 messages 数组中
        val messagesSection = body.substringAfter("\"messages\":").substringBeforeLast(']')
        assertTrue(!messagesSection.contains("\"role\":\"system\""))
    }

    @Test
    fun `anthropic 流式请求带 stream 标记`() {
        val body = bodyOf(AnthropicChatProtocol.buildRequest(anthropicConfig(), messages, stream = true))
        assertTrue(body.contains("\"stream\":true"))
    }

    // ---- Anthropic 响应解析 ----

    @Test
    fun `anthropic 非流式响应解析正文与用量`() {
        val body =
            """{"content":[{"type":"text","text":"你好！"}],"usage":{"input_tokens":12,"output_tokens":5},
               "stop_reason":"end_turn"}""".replace("\n", "").replace(" ", "")
        val content = AnthropicChatProtocol.parseContent(body)

        assertEquals("你好！", content.text)
        assertEquals(12, content.usage?.promptTokens)
        assertEquals(5, content.usage?.completionTokens)
        assertEquals("end_turn", content.finishReason)
    }

    @Test
    fun `anthropic 流式 content_block_delta 产出正文增量`() {
        val data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"片段"}}"""
        val result = AnthropicChatProtocol.parseStreamData(data)

        assertEquals(StreamParseResult.Delta("片段"), result)
    }

    @Test
    fun `openai 非流式解析 reasoning_content 与正文分离`() {
        val body =
            """{"choices":[{"message":{"role":"assistant","content":"答案","reasoning_content":"先分析"}}]}"""
        val content = OpenAiChatProtocol.parseContent(body)

        assertEquals("答案", content.text)
        assertEquals("先分析", content.thinking)
    }

    @Test
    fun `openai 流式解析 reasoning_content 增量`() {
        val data =
            """{"choices":[{"delta":{"role":"assistant","content":"","reasoning_content":"推理"}}]}"""
        assertEquals(StreamParseResult.Delta("", "推理"), OpenAiChatProtocol.parseStreamData(data))
    }

    @Test
    fun `anthropic thinking block 与正文分离`() {
        val body =
            """{"content":[{"type":"thinking","thinking":"先想"},{"type":"text","text":"答案"}]}"""
        val content = AnthropicChatProtocol.parseContent(body)

        assertEquals("答案", content.text)
        assertEquals("先想", content.thinking)
    }

    @Test
    fun `anthropic 流式 message_start 与 message_delta 产出用量`() {
        val start =
            AnthropicChatProtocol.parseStreamData(
                """{"type":"message_start","usage":{"input_tokens":30,"output_tokens":1}}""",
            )
        val delta =
            AnthropicChatProtocol.parseStreamData(
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},
                   "usage":{"input_tokens":0,"output_tokens":12}}""".replace("\n", "").replace(" ", ""),
            )

        assertEquals(30, (start as StreamParseResult.UsageUpdate).usage.promptTokens)
        assertEquals(12, (delta as StreamParseResult.UsageUpdate).usage.completionTokens)
    }

    @Test
    fun `anthropic 流式忽略无关事件与坏 JSON`() {
        assertEquals(
            StreamParseResult.Ignore,
            AnthropicChatProtocol.parseStreamData("""{"type":"content_block_stop"}"""),
        )
        assertEquals(StreamParseResult.Ignore, AnthropicChatProtocol.parseStreamData("{bad"))
    }

    // ---- Gemini 请求 ----

    @Test
    fun `gemini API key stays in header and streaming adds alt sse`() {
        val nonStream = GeminiChatProtocol.buildRequest(geminiConfig(), messages, stream = false)
        val stream = GeminiChatProtocol.buildRequest(geminiConfig(), messages, stream = true)

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            nonStream.url.toString(),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?alt=sse",
            stream.url.toString(),
        )
        assertEquals("gemini-key", nonStream.header("x-goog-api-key"))
        assertEquals(null, nonStream.url.queryParameter("key"))
    }

    @Test
    fun `gemini key 中的特殊字符不会进入 URL`() {
        val apiKey = "key+with&reserved=chars%"
        val config = geminiConfig().copy(apiKey = apiKey)
        val request = GeminiChatProtocol.buildRequest(config, messages, stream = true)

        assertEquals(apiKey, request.header("x-goog-api-key"))
        assertEquals(null, request.url.queryParameter("key"))
        assertEquals("sse", request.url.queryParameter("alt"))
    }

    @Test
    fun `gemini assistant 映射为 model 且 system 走 systemInstruction`() {
        val body = bodyOf(GeminiChatProtocol.buildRequest(geminiConfig(), messages, stream = false))

        assertTrue(body.contains("\"role\":\"model\""))
        assertTrue(!body.contains("\"role\":\"assistant\""))
        assertTrue(body.contains("\"systemInstruction\":{\"parts\":[{\"text\":\"你是助手\"}]}"))
    }

    // ---- Gemini 响应解析 ----

    @Test
    fun `gemini 非流式响应解析正文与用量`() {
        val body =
            """{"candidates":[{"content":{"parts":[{"text":"答案"}],"role":"model"},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4,"totalTokenCount":14}}"""
                .replace("\n", "").replace(" ", "")
        val content = GeminiChatProtocol.parseContent(body)

        assertEquals("答案", content.text)
        assertEquals(10, content.usage?.promptTokens)
        assertEquals(4, content.usage?.completionTokens)
        assertEquals("STOP", content.finishReason)
    }

    @Test
    fun `gemini 流式 chunk 产出正文增量`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":"逐字"}],"role":"model"}}]}"""
        assertEquals(StreamParseResult.Delta("逐字"), GeminiChatProtocol.parseStreamData(data))
    }

    @Test
    fun `gemini thought part 作为思维链增量`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":"推理","thought":true}],"role":"model"}}]}"""
        assertEquals(StreamParseResult.Delta("", "推理"), GeminiChatProtocol.parseStreamData(data))
    }

    @Test
    fun `gemini 流式末帧携带用量`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":20,"totalTokenCount":28}}"""
                .replace("\n", "").replace(" ", "")
        val result = GeminiChatProtocol.parseStreamData(data)

        assertEquals(28, (result as StreamParseResult.UsageUpdate).usage.totalTokens)
    }

    // ---- 分发 ----

    @Test
    fun `按类型分发到对应协议`() {
        assertTrue(chatProtocolFor(ApiType.OPENAI) is OpenAiChatProtocol)
        assertTrue(chatProtocolFor(ApiType.CUSTOM) is OpenAiChatProtocol)
        assertTrue(chatProtocolFor(ApiType.ANTHROPIC) is AnthropicChatProtocol)
        assertTrue(chatProtocolFor(ApiType.GEMINI) is GeminiChatProtocol)
    }
}
