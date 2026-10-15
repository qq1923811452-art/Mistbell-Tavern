package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 usage 提取单测（JVM）：多协议 SSE 帧 → 真实 usage 的映射（项目②）。
 *
 * 覆盖要点（与 ChatProtocolTest 的请求/正文用例互补，聚焦用量管道）：
 * - OpenAI/Gemini **同一帧同时带正文与 usage** 时，usage 必须随 Delta 一并带出，
 *   否则末帧用量会被 `delta ?: usageUpdate ?: Ignore` 丢掉（P2-4）；
 * - 仅带 usage、无正文的帧仍产出 UsageUpdate；
 * - DeepSeek 缓存字段（prompt_cache_hit_tokens / prompt_cache_miss_tokens）映射正确；
 * - Anthropic message_delta 不重发 input_tokens → promptTokens 恒为 0，
 *   交由上层合并器「新值为 0 = 缺省 → 保留旧值」保住 message_start 的输入量（P2-1）。
 */
class StreamParseUsageTest {
    @Test
    fun `openai 同帧正文与 usage 时 Delta 携带 usage`() {
        val data =
            """{"choices":[{"delta":{"content":"答"}}],
                "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""
        val result = OpenAiChatProtocol.parseStreamData(data)
        assertTrue("同帧正文+usage 应产出 Delta", result is StreamParseResult.Delta)
        val delta = result as StreamParseResult.Delta
        assertEquals("答", delta.text)
        assertNotNull("同帧正文+usage 时 usage 不能丢（P2-4）", delta.usage)
        assertEquals(12, delta.usage?.totalTokens)
    }

    @Test
    fun `openai 仅 usage 帧产出 UsageUpdate 且缓存字段映射正确`() {
        val data =
            """{"choices":[],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,
                    "prompt_cache_hit_tokens":90,"prompt_cache_miss_tokens":10}}"""
        val result = OpenAiChatProtocol.parseStreamData(data)
        assertTrue("仅 usage 帧应产出 UsageUpdate", result is StreamParseResult.UsageUpdate)
        val usage = (result as StreamParseResult.UsageUpdate).usage
        assertEquals(100, usage.promptTokens)
        assertEquals(5, usage.completionTokens)
        assertEquals(105, usage.totalTokens)
        // 缓存字段名（prompt_cache_hit_tokens / prompt_cache_miss_tokens）映射
        assertEquals(90, usage.cacheHitTokens)
        assertEquals(10, usage.cacheMissTokens)
    }

    @Test
    fun `openai prompt_tokens_details 缓存字段能计算命中率`() {
        val data =
            """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,
                "prompt_tokens_details":{"cached_tokens":80}}}"""
        val result = OpenAiChatProtocol.parseStreamData(data) as StreamParseResult.UsageUpdate
        assertEquals(80, result.usage.reportedCacheHitTokens)
        assertEquals(20, result.usage.reportedCacheMissTokens)
        assertTrue(result.usage.cacheTelemetryAvailable)
    }

    @Test
    fun `openai 无 usage 的纯正文帧 usage 为空`() {
        val data = """{"choices":[{"delta":{"content":"片段"}}]}"""
        val result = OpenAiChatProtocol.parseStreamData(data) as StreamParseResult.Delta
        assertEquals("片段", result.text)
        assertNull(result.usage)
    }

    @Test
    fun `gemini 同帧正文与用量时 Delta 携带 usage`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":"答"}],"role":"model"},"finishReason":"STOP"}],
                "usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":20,"totalTokenCount":28}}"""
        val result = GeminiChatProtocol.parseStreamData(data)
        assertTrue("同帧正文+用量应产出 Delta", result is StreamParseResult.Delta)
        val delta = result as StreamParseResult.Delta
        assertEquals("答", delta.text)
        assertEquals(28, delta.usage?.totalTokens)
        assertEquals(8, delta.usage?.promptTokens)
        assertEquals(20, delta.usage?.completionTokens)
    }

    @Test
    fun `gemini cachedContentTokenCount 能映射为缓存命中`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}],
                "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"totalTokenCount":105,
                    "cachedContentTokenCount":80}}"""
        val result = GeminiChatProtocol.parseStreamData(data) as StreamParseResult.UsageUpdate
        assertEquals(80, result.usage.reportedCacheHitTokens)
        assertEquals(20, result.usage.reportedCacheMissTokens)
        assertTrue(result.usage.cacheTelemetryAvailable)
    }

    @Test
    fun `gemini 仅用量帧产出 UsageUpdate`() {
        val data =
            """{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}],
                "usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":20,"totalTokenCount":28}}"""
        val result = GeminiChatProtocol.parseStreamData(data)
        assertTrue("无正文仅用量帧应产出 UsageUpdate", result is StreamParseResult.UsageUpdate)
        assertEquals(28, (result as StreamParseResult.UsageUpdate).usage.totalTokens)
    }

    @Test
    fun `anthropic message_start 带出输入量`() {
        val data = """{"type":"message_start","usage":{"input_tokens":30,"output_tokens":1}}"""
        val usage = (AnthropicChatProtocol.parseStreamData(data) as StreamParseResult.UsageUpdate).usage
        assertEquals(30, usage.promptTokens)
        assertEquals(1, usage.completionTokens)
    }

    @Test
    fun `anthropic message_delta 的 promptTokens 为 0 交合并器保留 message_start 真值`() {
        // message_delta 不重发 input_tokens（缺省 → 默认 0）。若像旧实现那样把它当真值
        // 覆盖出去，会把 message_start 的输入量清零（P2-1）；合并器的字段级规则
        // 「新值为 0（= 缺省）则保留旧值」依赖这里如实回 0。
        val data = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}"""
        val usage = (AnthropicChatProtocol.parseStreamData(data) as StreamParseResult.UsageUpdate).usage
        assertEquals(0, usage.promptTokens)
        assertEquals(12, usage.completionTokens)
    }

    @Test
    fun `anthropic 流式正文增量不带 usage`() {
        val data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"片段"}}"""
        val result = AnthropicChatProtocol.parseStreamData(data)
        assertTrue(result is StreamParseResult.Delta)
        assertNull((result as StreamParseResult.Delta).usage)
    }
}
