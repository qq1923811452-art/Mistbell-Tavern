package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextWindowTest {
    @Test
    fun keepsOnlyNewestFortyTurnsByDefaultAndSummarizesOlderMessages() {
        val history = (1..100).map { index ->
            PromptMessage(if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT, "message-$index")
        }
        val result = ConversationContextWindowBuilder.build(history)
        assertEquals(80, result.recentMessages.size)
        assertEquals("message-21", result.recentMessages.first().content)
        assertEquals("message-100", result.recentMessages.last().content)
        assertTrue(result.earlierSummary.contains("message-1"))
        assertTrue(result.earlierSummary.contains("message-20"))
        assertTrue(result.earlierSummary.length <= ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS)
        assertTrue(!result.earlierSummary.contains("message-21"))
    }

    @Test
    fun defaultsUseConservativeCharacterBudget() {
        assertEquals(40, ConversationContextWindowBuilder.DEFAULT_RECENT_TURNS)
        assertEquals(4_000, ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS)
        assertEquals(24_000, ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS)
    }

    @Test
    fun summaryCanBeDisabledAndRecentWindowCanBeConfigured() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "old"),
            PromptMessage(PromptRole.ASSISTANT, "old reply"),
            PromptMessage(PromptRole.USER, "new"),
            PromptMessage(PromptRole.ASSISTANT, "new reply"),
        )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1, summaryMaxChars = 0)
        assertEquals("", result.earlierSummary)
        assertEquals(listOf("new", "new reply"), result.recentMessages.map { it.content })
    }

    @Test
    fun recentHistoryAlsoRespectsCharacterBudget() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "old"),
            PromptMessage(PromptRole.ASSISTANT, "older reply"),
            PromptMessage(PromptRole.USER, "new"),
            PromptMessage(PromptRole.ASSISTANT, "new reply"),
        )
        val result = ConversationContextWindowBuilder.build(
            history,
            recentTurns = 2,
            summaryMaxChars = 300,
            maxHistoryChars = 12,
        )
        assertEquals(listOf("new", "new reply"), result.recentMessages.map { it.content })
    }

    @Test
    fun recentWindowCountsUserTurnsAndKeepsMultipleAssistantMessagesInTurn() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "user-1"),
            PromptMessage(PromptRole.ASSISTANT, "assistant-1a"),
            PromptMessage(PromptRole.ASSISTANT, "assistant-1b"),
            PromptMessage(PromptRole.USER, "user-2"),
            PromptMessage(PromptRole.ASSISTANT, "assistant-2"),
        )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1)
        assertEquals(listOf("user-2", "assistant-2"), result.recentMessages.map { it.content })
        assertTrue(result.earlierSummary.contains("user-1"))
    }

    @Test
    fun historyStartingWithAssistantDoesNotLeaveAnOrphanInRecentWindow() {
        val history = listOf(
            PromptMessage(PromptRole.ASSISTANT, "orphaned reply"),
            PromptMessage(PromptRole.USER, "first user"),
            PromptMessage(PromptRole.ASSISTANT, "first reply"),
        )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 20)
        assertEquals(listOf("first user", "first reply"), result.recentMessages.map { it.content })
        assertTrue(result.earlierSummary.contains("orphaned reply"))
    }

    @Test
    fun consecutiveUserMessagesCountAsSeparateUserTurns() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "first user"),
            PromptMessage(PromptRole.USER, "second user"),
            PromptMessage(PromptRole.ASSISTANT, "reply to second"),
        )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1)
        assertEquals(listOf("second user", "reply to second"), result.recentMessages.map { it.content })
        assertTrue(result.earlierSummary.contains("first user"))
    }

    @Test
    fun summaryDoesNotSplitEmojiSurrogatePairAtBudgetBoundary() {
        val history = listOf(PromptMessage(PromptRole.USER, "ab😀z"))
        val result = ConversationContextWindowBuilder.build(
            history,
            recentTurns = 0,
            summaryMaxChars = 6,
        )
        assertEquals("用户：ab", result.earlierSummary)
        assertTrue(result.earlierSummary.none { Character.isSurrogate(it) })
    }

    @Test
    fun emptyHistoryReturnsEmptyWindow() {
        val result = ConversationContextWindowBuilder.build(emptyList())
        assertEquals("", result.earlierSummary)
        assertTrue(result.recentMessages.isEmpty())
    }

    @Test
    fun zeroRecentTurnsMovesEntireHistoryIntoSummary() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "opening setup"),
            PromptMessage(PromptRole.ASSISTANT, "opening reply"),
            PromptMessage(PromptRole.USER, "later event"),
            PromptMessage(PromptRole.ASSISTANT, "latest old reply"),
        )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 0)
        assertTrue(result.recentMessages.isEmpty())
        assertTrue(result.earlierSummary.contains("opening setup"))
        assertTrue(result.earlierSummary.contains("latest old reply"))
    }

    @Test
    fun summaryKeepsOpeningAndEndingOfOlderHistory() {
        val history = (1..30).map { index ->
            PromptMessage(if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT, "old-message-$index")
        } + PromptMessage(PromptRole.USER, "current user")
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1, summaryMaxChars = 180)
        assertTrue(result.earlierSummary.contains("old-message-1"))
        assertTrue(result.earlierSummary.contains("old-message-30"))
    }

    @Test
    fun keepsLongRoleplayRepliesWholeWithinConfiguredBudget() {
        val longReply = "剧".repeat(200)
        val history = buildList {
            repeat(40) { index ->
                add(PromptMessage(PromptRole.USER, "用户输入 $index"))
                add(PromptMessage(PromptRole.ASSISTANT, longReply))
            }
        }
        val result = ConversationContextWindowBuilder.build(history)
        assertEquals(80, result.recentMessages.size)
        assertTrue(result.recentMessages.all { it.content.length == longReply.length || it.role == PromptRole.USER })
        assertTrue(result.recentMessages.sumOf { it.content.length } <= 24_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeWindowSize() {
        ConversationContextWindowBuilder.build(emptyList(), recentTurns = -1)
    }

    @Test
    fun summaryStaysFrozenAcrossTurnsWithinTheSameTenMessageBlock() {
        val summaries = (1..5).map { turnCount ->
            val history = conversationWithTurns(turnCount)
            val result = ConversationContextWindowBuilder.build(history, recentTurns = 1)
            // The split stays at index 10 for one to five turns, so no recent turn may leak into the summary.
            assertEquals(history.drop(10), result.recentMessages)
            assertTrue(!result.earlierSummary.contains("turn-"))
            result.earlierSummary
        }
        assertEquals(1, summaries.distinct().size)
        assertTrue(summaries.first().contains("prefix-1"))
        assertTrue(summaries.first().contains("prefix-10"))
    }

    @Test
    fun summaryChangesWhenTheBoundaryCrossesIntoTheNextTenMessageBlock() {
        val withinBlock = conversationWithTurns(5)
        val nextBlock = conversationWithTurns(6)
        val frozen = ConversationContextWindowBuilder.build(withinBlock, recentTurns = 1)
        val shifted = ConversationContextWindowBuilder.build(nextBlock, recentTurns = 1)
        assertEquals(10, withinBlock.size - frozen.recentMessages.size)
        assertEquals(20, nextBlock.size - shifted.recentMessages.size)
        assertTrue(frozen.earlierSummary != shifted.earlierSummary)
        assertTrue(!frozen.earlierSummary.contains("turn-5-user"))
        assertTrue(shifted.earlierSummary.contains("turn-5-user"))
        assertTrue(!shifted.earlierSummary.contains("turn-6-user"))
        assertEquals(listOf("turn-6-user", "turn-6-assistant"), shifted.recentMessages.map { it.content })
    }

    @Test
    fun recentMessagesKeepEveryMessageAfterTheSummaryFreezesToABlock() {
        val history = (1..25).map { index ->
            PromptMessage(
                if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT,
                "message-$index",
            )
        }
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1)
        val splitAt = history.size - result.recentMessages.size
        assertEquals(20, splitAt)
        assertEquals(0, splitAt % ConversationContextWindowBuilder.SUMMARY_BLOCK_MESSAGES)
        assertEquals(history.drop(splitAt), result.recentMessages)
        assertEquals((21..25).map { "message-$it" }, result.recentMessages.map { it.content })
        assertTrue(result.earlierSummary.contains("message-1"))
        assertTrue(result.earlierSummary.contains("message-20"))
        assertTrue(!result.earlierSummary.contains("message-21"))
    }

    private fun conversationWithTurns(turnCount: Int): List<PromptMessage> = buildList {
        (1..10).forEach { index ->
            add(
                PromptMessage(
                    if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT,
                    "prefix-$index",
                ),
            )
        }
        (1..turnCount).forEach { turn ->
            add(PromptMessage(PromptRole.USER, "turn-$turn-user"))
            add(PromptMessage(PromptRole.ASSISTANT, "turn-$turn-assistant"))
        }
    }
}
