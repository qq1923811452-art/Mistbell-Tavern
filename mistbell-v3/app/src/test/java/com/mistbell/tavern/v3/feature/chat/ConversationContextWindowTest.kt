package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextWindowTest {
    @Test
    fun keepsOnlyNewestFortyTurnsByDefaultAndSummarizesOlderMessages() {
        val history =
            (1..100).map { index ->
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
        val history =
            listOf(
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
    fun characterBudgetMovesExcludedTurnsIntoSummaryInsteadOfLeavingAHole() {
        val history =
            listOf(
                PromptMessage(PromptRole.USER, "old"),
                PromptMessage(PromptRole.ASSISTANT, "older reply"),
                PromptMessage(PromptRole.USER, "new"),
                PromptMessage(PromptRole.ASSISTANT, "new reply"),
            )
        val result =
            ConversationContextWindowBuilder.build(
                history,
                recentTurns = 2,
                summaryMaxChars = 300,
                maxHistoryChars = 12,
            )
        assertEquals(2, result.archivedMessageCount)
        assertEquals(history.size, result.archivedMessageCount + result.recentMessages.size)
        assertEquals(history.drop(result.archivedMessageCount), result.recentMessages)
        assertTrue(result.earlierSummary.contains("old"))
        assertTrue(result.earlierSummary.contains("older reply"))
        assertTrue(!result.earlierSummary.contains("new"))
        assertEquals(12L, result.recentHistoryChars)
        assertTrue(!result.isOverHistoryBudget)
    }

    @Test
    fun recentWindowCountsUserTurnsAndKeepsMultipleAssistantMessagesInTurn() {
        val history =
            listOf(
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
        val history =
            listOf(
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
        val history =
            listOf(
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
        val result =
            ConversationContextWindowBuilder.build(
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
        val history =
            listOf(
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
        val history =
            (1..30).map { index ->
                PromptMessage(if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT, "old-message-$index")
            } + PromptMessage(PromptRole.USER, "current user")
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1, summaryMaxChars = 180)
        assertTrue(result.earlierSummary.contains("old-message-1"))
        assertTrue(result.earlierSummary.contains("old-message-30"))
    }

    @Test
    fun keepsLongRoleplayRepliesWholeWithinConfiguredBudget() {
        val longReply = "剧".repeat(200)
        val history =
            buildList {
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
    fun turnLimitUsesExactBoundaryInsteadOfExpandingToTenMessageBlocks() {
        val history =
            (1..25).map { index ->
                PromptMessage(
                    if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT,
                    "message-$index",
                )
            }
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1)
        assertEquals(history.drop(24), result.recentMessages)
        assertTrue(result.earlierSummary.contains("message-24"))
        assertTrue(!result.earlierSummary.contains("message-25"))
    }

    @Test
    fun tenMessageAlignmentNeverSplitsATurnWithMultipleAssistants() {
        val history =
            buildList {
                repeat(4) { turn ->
                    add(PromptMessage(PromptRole.USER, "user-$turn"))
                    add(PromptMessage(PromptRole.ASSISTANT, "reply-$turn-a"))
                    add(PromptMessage(PromptRole.ASSISTANT, "reply-$turn-b"))
                }
                add(PromptMessage(PromptRole.USER, "latest"))
                add(PromptMessage(PromptRole.ASSISTANT, "latest reply"))
            }
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1)
        assertEquals(history.drop(12), result.recentMessages)
        assertTrue(result.earlierSummary.contains("reply-3-b"))
        assertTrue(!result.earlierSummary.contains("latest"))
    }

    @Test
    fun budgetDoesNotKeepAnAssistantSuffixOfAnExcludedTurn() {
        val history =
            listOf(
                PromptMessage(PromptRole.USER, "old user is too long"),
                PromptMessage(PromptRole.ASSISTANT, "a"),
                PromptMessage(PromptRole.ASSISTANT, "b"),
                PromptMessage(PromptRole.USER, "new"),
                PromptMessage(PromptRole.ASSISTANT, "c"),
                PromptMessage(PromptRole.ASSISTANT, "d"),
            )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 2, maxHistoryChars = 7)
        assertEquals(history.drop(3), result.recentMessages)
        assertTrue(result.earlierSummary.contains("old user is too long"))
        assertTrue(result.earlierSummary.contains("助手：a"))
        assertTrue(result.earlierSummary.contains("助手：b"))
        assertEquals(5L, result.recentHistoryChars)
        assertTrue(!result.isOverHistoryBudget)
    }

    @Test
    fun budgetCountsConsecutiveUsersAsSeparateTurns() {
        val history =
            listOf(
                PromptMessage(PromptRole.USER, "too long"),
                PromptMessage(PromptRole.USER, "x"),
                PromptMessage(PromptRole.USER, "y"),
            )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 3, maxHistoryChars = 2)
        assertEquals(history.drop(1), result.recentMessages)
        assertTrue(result.earlierSummary.contains("too long"))
    }

    @Test
    fun oversizedLatestTurnStaysWholeAndReportsSoftBudgetOverflow() {
        val history =
            listOf(
                PromptMessage(PromptRole.USER, "old"),
                PromptMessage(PromptRole.ASSISTANT, "old reply"),
                PromptMessage(PromptRole.USER, "😀"),
                PromptMessage(PromptRole.ASSISTANT, "剧".repeat(100)),
                PromptMessage(PromptRole.ASSISTANT, "tail"),
            )
        val result = ConversationContextWindowBuilder.build(history, maxHistoryChars = 5)
        assertEquals(2, result.archivedMessageCount)
        assertEquals(history.size, result.archivedMessageCount + result.recentMessages.size)
        assertEquals(history.drop(result.archivedMessageCount), result.recentMessages)
        assertEquals(106L, result.recentHistoryChars)
        assertEquals(5, result.maxHistoryChars)
        assertTrue(result.isOverHistoryBudget)
        assertTrue(result.earlierSummary.contains("old reply"))
    }

    @Test
    fun zeroCharacterBudgetMovesEntireHistoryToOlder() {
        val history =
            listOf(
                PromptMessage(PromptRole.ASSISTANT, "opening"),
                PromptMessage(PromptRole.USER, "user"),
                PromptMessage(PromptRole.ASSISTANT, "reply"),
            )
        val result = ConversationContextWindowBuilder.build(history, maxHistoryChars = 0)
        assertTrue(result.recentMessages.isEmpty())
        assertTrue(result.earlierSummary.contains("opening"))
        assertTrue(result.earlierSummary.contains("reply"))
        assertEquals(0L, result.recentHistoryChars)
        assertTrue(!result.isOverHistoryBudget)
    }

    @Test
    fun disabledSummaryStillUsesFinalWholeTurnBudgetBoundary() {
        val history =
            listOf(
                PromptMessage(PromptRole.USER, "old long user"),
                PromptMessage(PromptRole.ASSISTANT, "old reply"),
                PromptMessage(PromptRole.USER, "x"),
                PromptMessage(PromptRole.ASSISTANT, "y"),
            )
        val result = ConversationContextWindowBuilder.build(history, summaryMaxChars = 0, maxHistoryChars = 2)
        assertEquals("", result.earlierSummary)
        assertEquals(2, result.archivedMessageCount)
        assertEquals(history.size, result.archivedMessageCount + result.recentMessages.size)
        assertEquals(history.drop(result.archivedMessageCount), result.recentMessages)
        assertEquals(2L, result.recentHistoryChars)
        assertTrue(!result.isOverHistoryBudget)
    }

    @Test
    fun assistantOnlyHistoryRemainsAnIndivisibleOpeningSegment() {
        val history =
            listOf(
                PromptMessage(PromptRole.ASSISTANT, "opening"),
                PromptMessage(PromptRole.ASSISTANT, "continuation"),
            )
        val result = ConversationContextWindowBuilder.build(history, recentTurns = 1, maxHistoryChars = 1)
        assertEquals(history, result.recentMessages)
        assertEquals("", result.earlierSummary)
        assertTrue(result.isOverHistoryBudget)
        val olderOnly = ConversationContextWindowBuilder.build(history, recentTurns = 0)
        assertTrue(olderOnly.recentMessages.isEmpty())
        assertTrue(olderOnly.earlierSummary.contains("opening"))
    }

    @Test
    fun summaryRemainsValidUtf16AcrossAllSmallBudgets() {
        val history =
            listOf(
                PromptMessage(PromptRole.ASSISTANT, "ab😀z"),
                PromptMessage(PromptRole.USER, "😀latest😀"),
                PromptMessage(PromptRole.ASSISTANT, "xy😀z"),
            )
        for (budget in 0..50) {
            val result = ConversationContextWindowBuilder.build(history, recentTurns = 0, summaryMaxChars = budget)
            assertTrue(result.earlierSummary.length <= budget)
            val text = result.earlierSummary
            text.forEachIndexed { index, char ->
                if (char.isHighSurrogate()) assertTrue(index + 1 < text.length && text[index + 1].isLowSurrogate())
                if (char.isLowSurrogate()) assertTrue(index > 0 && text[index - 1].isHighSurrogate())
            }
        }
    }

    @Test
    fun archivedCountDescribesSummaryInputEvenWhenSummaryIsLossyOrDisabled() {
        val history =
            listOf(
                PromptMessage(PromptRole.ASSISTANT, "opening"),
                PromptMessage(PromptRole.USER, "old user"),
                PromptMessage(PromptRole.ASSISTANT, "old reply"),
                PromptMessage(PromptRole.USER, "new user"),
                PromptMessage(PromptRole.ASSISTANT, "new reply"),
            )
        for (turns in 0..3) {
            for (characters in listOf(0, 1, 17, 100)) {
                for (summaryChars in listOf(0, 1, 100)) {
                    val result = ConversationContextWindowBuilder.build(history, turns, summaryChars, characters)
                    val splitAt = result.archivedMessageCount
                    assertEquals(history.size, splitAt + result.recentMessages.size)
                    assertEquals(history.drop(splitAt), result.recentMessages)
                    val summaryInput =
                        ConversationContextWindowBuilder.build(
                            history.take(splitAt),
                            recentTurns = 0,
                            summaryMaxChars = summaryChars,
                        )
                    assertEquals(summaryInput.earlierSummary, result.earlierSummary)
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeCharacterBudget() {
        ConversationContextWindowBuilder.build(emptyList(), maxHistoryChars = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeSummaryBudget() {
        ConversationContextWindowBuilder.build(emptyList(), summaryMaxChars = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSystemMessagesInHistory() {
        ConversationContextWindowBuilder.build(listOf(PromptMessage(PromptRole.SYSTEM, "system")))
    }
}
