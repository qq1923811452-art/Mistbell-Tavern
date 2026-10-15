package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.SseParser
import com.mistbell.tavern.android.data.vector.VectorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示词重排相关纯逻辑：@D 深度只数历史消息、向量召回去重、流式 usage 解析 */
class PromptBuilderMemoryLayoutTest {
    // ---- atDepthIndex：@D 落点对尾部 system 块免疫 ----

    private fun chat(vararg contents: String) = contents.map { ChatMessage(role = "assistant", content = it) }

    @Test
    fun `深度为 1 且将追加用户消息时_落在数组末尾`() {
        val messages = chat("一", "二", "三")
        assertEquals(3, PromptBuilder.atDepthIndex(messages, depth = 1, hasFinalUserMessage = true))
    }

    @Test
    fun `深度为 3 时_插入点之后留 2 条历史加最终用户消息`() {
        val messages = chat("一", "二", "三", "四", "五")
        // 期望：[.., 三, @D, 四, 五] + 用户消息 → 倒数 3 条 = 四、五、用户
        assertEquals(3, PromptBuilder.atDepthIndex(messages, depth = 3, hasFinalUserMessage = true))
    }

    @Test
    fun `尾部夹着 system 块时_深度仍按历史消息计数`() {
        val messages =
            chat("一", "二", "三", "四", "五") +
                listOf(
                    ChatMessage(role = "system", content = "作者注释"),
                    ChatMessage(role = "system", content = "长期记忆"),
                )
        // system 块不占倒数位：深度 2 → 插入点之后应留 1 条历史（五）+ 最终用户消息 = 2 条
        // 若按数组条数计数则会误落到 5 之前（system 块被算进倒数位）
        assertEquals(4, PromptBuilder.atDepthIndex(messages, depth = 2, hasFinalUserMessage = true))
    }

    @Test
    fun `system 块位于历史中间时_同样只数历史`() {
        val messages =
            listOf(
                ChatMessage(role = "assistant", content = "一"),
                ChatMessage(role = "system", content = "世界书"),
                ChatMessage(role = "assistant", content = "二"),
                ChatMessage(role = "assistant", content = "三"),
            )
        // 深度 1 → 紧贴最终用户消息之前，即数组末尾（system 块不占倒数位）
        assertEquals(4, PromptBuilder.atDepthIndex(messages, depth = 1, hasFinalUserMessage = true))
    }

    @Test
    fun `无新用户消息场景_深度 1 留最后一条历史在插入点之后`() {
        val messages = chat("一", "二", "三")
        assertEquals(2, PromptBuilder.atDepthIndex(messages, depth = 1, hasFinalUserMessage = false))
    }

    @Test
    fun `深度超过历史条数时_钳到 0`() {
        val messages = chat("一")
        assertEquals(0, PromptBuilder.atDepthIndex(messages, depth = 9, hasFinalUserMessage = true))
    }

    @Test
    fun `空列表返回 0`() {
        assertEquals(0, PromptBuilder.atDepthIndex(emptyList(), depth = 1, hasFinalUserMessage = true))
    }

    // ---- buildVectorMemoryContextForPrompt：重复注入去重 ----

    private fun result(
        content: String,
        score: Float = 0.9f,
    ) = VectorStore.SearchResult(id = "vec_1", score = score, content = content, metadata = emptyMap())

    @Test
    fun `内容已被结构化记忆覆盖的向量行被丢弃`() {
        val injected = listOf("蒋珞要求 user 放学后带芋泥口味的千层")
        // summary 副本形态："标题: 正文 [类型]"——正文被包含即为重复
        val out =
            PromptBuilder.buildVectorMemoryContextForPrompt(
                results = listOf(result("蒋珞要千层: 蒋珞要求 user 放学后带芋泥口味的千层 [preference]")),
                alreadyInjected = injected,
            )
        assertEquals("", out)
    }

    @Test
    fun `未覆盖的向量行保留并带标题`() {
        val injected = listOf("蒋珞要求 user 放学后带芋泥口味的千层")
        val out =
            PromptBuilder.buildVectorMemoryContextForPrompt(
                results = listOf(result("昨晚课堂上张老师点名提问了三次")),
                alreadyInjected = injected,
            )
        assertTrue(out.contains("## Relevant Past Conversations"))
        assertTrue(out.contains("昨晚课堂上张老师点名提问了三次"))
    }

    @Test
    fun `低分结果与重复结果全部过滤时返回空串`() {
        val injected = listOf("user 的名字是墨轩")
        val duplicateResult = result("user 的名字是墨轩 [identity]", score = 0.9f)
        val lowScoreResult = result("无关的历史片段", score = 0.3f)
        val out =
            PromptBuilder.buildVectorMemoryContextForPrompt(
                results = listOf(duplicateResult, lowScoreResult),
                alreadyInjected = injected,
            )
        assertEquals("", out)
    }

    @Test
    fun `无注入记忆时不做去重_结果原样保留`() {
        val out =
            PromptBuilder.buildVectorMemoryContextForPrompt(
                results = listOf(result("任意历史片段")),
                alreadyInjected = emptyList(),
            )
        assertTrue(out.contains("1. 任意历史片段"))
    }

    @Test
    fun `记忆正文比向量行长时_双向包含判定仍视为重复`() {
        val out =
            PromptBuilder.buildVectorMemoryContextForPrompt(
                results = listOf(result("user 讨厌被叫主人")),
                alreadyInjected = listOf("user 讨厌被叫主人，尤其是带嘲讽的语气"),
            )
        assertEquals("", out)
    }

    @Test
    fun `过短文本不参与包含判定_不被误删`() {
        val out =
            PromptBuilder.buildVectorMemoryContextForPrompt(
                results = listOf(result("片段")),
                alreadyInjected = listOf("这是一条足够长的记忆正文片段内容"),
            )
        // 短文本不参与判定（长度 < 8），因此这条应保留
        assertTrue(out.contains("片段"))
    }

    // ---- MemoryContext：会话未裁剪时丢弃召回块（整段历史已可见，召回片段必然重复） ----

    private val structured = ChatMessage(role = "system", content = "## Known Information")
    private val recall = ChatMessage(role = "system", content = "## Relevant Past Conversations")

    @Test
    fun `会话未被裁剪时只注入结构化记忆_丢弃召回块`() {
        val context = PromptBuilder.MemoryContext(structuredBlock = structured, recallBlock = recall)
        assertEquals(listOf(structured), context.messagesFor(historyTruncated = false))
    }

    @Test
    fun `会话被裁剪时两块都注入`() {
        val context = PromptBuilder.MemoryContext(structuredBlock = structured, recallBlock = recall)
        assertEquals(listOf(structured, recall), context.messagesFor(historyTruncated = true))
    }

    @Test
    fun `预算口径始终按两块计算_避免低估撑爆上下文`() {
        val context = PromptBuilder.MemoryContext(structuredBlock = structured, recallBlock = recall)
        assertEquals(listOf(structured, recall), context.allMessages())
    }

    @Test
    fun `空上下文两种情况都为空`() {
        val empty = PromptBuilder.MemoryContext()
        assertEquals(emptyList<ChatMessage>(), empty.messagesFor(historyTruncated = false))
        assertEquals(emptyList<ChatMessage>(), empty.messagesFor(historyTruncated = true))
        assertEquals(emptyList<ChatMessage>(), empty.allMessages())
    }

    @Test
    fun `只有召回块时_未裁剪则什么都不注入`() {
        val context = PromptBuilder.MemoryContext(recallBlock = recall)
        assertEquals(emptyList<ChatMessage>(), context.messagesFor(historyTruncated = false))
        assertEquals(listOf(recall), context.messagesFor(historyTruncated = true))
    }

    @Test
    fun `提示词缓存指纹稳定且固定提示词变更后失效`() {
        val original =
            listOf(
                "prompt-cache-fingerprint-v1",
                "owner-1",
                "character-1",
                "session-1",
                "deepseek-flash",
                "system",
                "固定角色提示词",
                "世界书作者注释",
            )
        val fingerprint = PromptBuilder.promptCacheFingerprint(original)

        assertEquals(fingerprint, PromptBuilder.promptCacheFingerprint(original.toList()))
        val changedPrompt =
            original.mapIndexed { index, value ->
                if (index == 6) "修改后的角色提示词" else value
            }
        assertTrue(fingerprint != PromptBuilder.promptCacheFingerprint(changedPrompt))
    }

    // ---- SseParser：流式 usage（缓存统计）解析 ----

    @Test
    fun `流式 chunk 中的 usage 可被解析_用于缓存命中率观测`() {
        val line =
            "{\"choices\":[],\"usage\":{" +
                "\"prompt_tokens\":8512," +
                "\"completion_tokens\":412," +
                "\"prompt_cache_hit_tokens\":7357," +
                "\"prompt_cache_miss_tokens\":1155" +
                "}}"
        val chunk = SseParser.parseChunk(line)
        assertNotNull(chunk)
        assertEquals(7357, chunk!!.usage!!.cacheHitTokens)
        assertEquals(1155, chunk.usage!!.cacheMissTokens)
        assertEquals(8512, chunk.usage!!.promptTokens)
        assertNull(SseParser.contentOf(chunk)) // 该 chunk 无正文增量，不应产生输出
    }

    @Test
    fun `无 usage 字段的 chunk 返回 null_调用方按网关不提供统计处理`() {
        val chunk = SseParser.parseChunk("""{"choices":[{"delta":{"content":"你好"}}]}""")
        assertNotNull(chunk)
        assertNull(chunk!!.usage)
        assertEquals("你好", SseParser.contentOf(chunk))
    }
}
