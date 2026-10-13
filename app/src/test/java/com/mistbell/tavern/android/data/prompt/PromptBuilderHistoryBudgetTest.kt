package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PromptBuilder 历史截断逻辑回归测试（ROADMAP M2-2 首批）。
 *
 * 锁定两条语义（本轮修复）：
 * 1. 预算内从最新往回【连续】选取——修复前会跳过中间放不下却继续选更旧的，
 *    产生非连续历史片段；
 * 2. 最新一条无条件纳入——保持历史实现"预算极小也带上最近一轮"的行为。
 */
class PromptBuilderHistoryBudgetTest {
    private fun msg(
        id: String,
        content: String,
    ) = MessageEntity(
        id = id,
        sessionId = "s",
        ownerId = "o",
        characterId = "c",
        role = "user",
        content = content,
        thinking = null,
        createdAt = "2026-01-01T00:00:00.000Z",
        memoryIdsJson = "",
        swipesJson = "",
        swipeIndex = 0,
        thinkingSwipesJson = "",
    )

    private val small = "a".repeat(16) // 远小于预算下限
    private val huge = "x".repeat(20_000) // 远超预算下限(256 token ≈ 1024 ASCII 字符)

    @Test
    fun `预算充足时全部历史按时间序保留`() {
        val history = listOf(msg("m1", small), msg("m2", small), msg("m3", small))
        val selected =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = history,
                currentMessages = listOf(ChatMessage("system", small)),
                currentUserMessage = small,
                contextTokenLimit = 8192,
            )
        assertEquals(listOf("m1", "m2", "m3"), selected.map { it.id })
    }

    @Test
    fun `空历史返回空`() {
        val selected =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = emptyList(),
                currentMessages = listOf(ChatMessage("system", small)),
                currentUserMessage = small,
                contextTokenLimit = 4096,
            )
        assertEquals(0, selected.size)
    }

    @Test
    fun `预算极小时最新一条仍被纳入`() {
        // 三条都远超 256 token 下限；预算耗尽也应保留最新的那条
        val history = listOf(msg("old", huge), msg("mid", huge), msg("new", huge))
        val selected =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = history,
                currentMessages = listOf(ChatMessage("system", small)),
                currentUserMessage = small,
                contextTokenLimit = 512,
            )
        assertEquals(listOf("new"), selected.map { it.id })
    }

    @Test
    fun `前缀缓存快照计入历史预算`() {
        val history = listOf(msg("m1", small), msg("m2", small))
        val args = listOf(ChatMessage("system", small))
        val withoutSnapshots =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = history,
                currentMessages = args,
                currentUserMessage = small,
                contextTokenLimit = 512,
            )
        val withSnapshots =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = history,
                currentMessages = args,
                currentUserMessage = small,
                contextTokenLimit = 512,
                promptCacheSnapshots =
                    mapOf(
                        "m2" to listOf(ChatMessage("system", huge)),
                    ),
            )

        assertEquals(listOf("m1", "m2"), withoutSnapshots.map { it.id })
        assertEquals(listOf("m2"), withSnapshots.map { it.id })
    }

    @Test
    fun `前缀缓存快照中的渲染用户消息计入历史预算`() {
        val history = listOf(msg("m1", small), msg("m2", small))
        val selected =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = history,
                currentMessages = listOf(ChatMessage("system", small)),
                currentUserMessage = small,
                contextTokenLimit = 512,
                promptCacheSnapshots =
                    mapOf(
                        "m2" to listOf(
                            ChatMessage("system", small),
                            ChatMessage("user", huge),
                        ),
                    ),
            )

        assertEquals(listOf("m2"), selected.map { it.id })
    }

    @Test
    fun `截断产生从最新往回的连续前缀而非跳选`() {
        // 最新与中间消息很小能装下，最旧的消息巨大装不下：
        // 连续前缀语义 => [mid, new]；跳选（旧缺陷）=> 可能出现 [old, new] 之类非连续结果
        val history = listOf(msg("old", huge), msg("mid", small), msg("new", small))
        val selected =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = history,
                currentMessages = listOf(ChatMessage("system", small)),
                currentUserMessage = small,
                contextTokenLimit = 512,
            )
        assertEquals(listOf("mid", "new"), selected.map { it.id })
    }

    @Test
    fun `中间放不下的消息之后不再选更旧消息`() {
        // 最新小；中间巨大（放不下）；最旧又变小（若恢复"跳过继续选"的旧行为会被选中）
        val history = listOf(msg("old-small", small), msg("mid-huge", huge), msg("new", small))
        val selected =
            PromptBuilder.selectHistoryWithinBudget(
                recentMessages = history,
                currentMessages = listOf(ChatMessage("system", small)),
                currentUserMessage = small,
                contextTokenLimit = 512,
            )
        assertEquals(
            "预算在 mid-huge 处耗尽后必须停止，不允许再捡回更旧的 old-small",
            listOf("new"),
            selected.map { it.id },
        )
    }
}
