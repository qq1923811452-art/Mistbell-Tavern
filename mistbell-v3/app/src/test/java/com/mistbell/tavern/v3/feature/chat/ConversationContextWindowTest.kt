package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextWindowTest {
    @Test
    fun keepsOnlyNewestFortyTurnsByDefaultAndSummarizesOlderMessages() {
        val history = (1..100).map { index ->
            PromptMessage(
                if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT,
                "message-$index",
            )
        }

        val result = ConversationContextWindowBuilder.build(history)

        assertEquals(80, result.recentMessages.size)
        assertEquals("message-21", result.recentMessages.first().content)
        assertEquals("message-100", result.recentMessages.last().content)
        assertTrue(result.earlierSummary.contains("message-20"))
        assertTrue(result.earlierSummary.length <= ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS)
        assertTrue(!result.earlierSummary.contains("message-21"))
    }

    @Test
    fun defaultsProvideLongFormHistoryBudget() {
        assertEquals(40, ConversationContextWindowBuilder.DEFAULT_RECENT_TURNS)
        assertEquals(4_000, ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS)
        assertEquals(70_000, ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS)
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
    fun recentWindowDoesNotBeginWithAnOrphanAssistantMessage() {
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
    fun keepsLongRoleplayRepliesWholeWithinTheDefaultBudget() {
        val longReply = "剧".repeat(1_000)
        val history = buildList {
            repeat(40) { index ->
                add(PromptMessage(PromptRole.USER, "用户输入 $index"))
                add(PromptMessage(PromptRole.ASSISTANT, longReply))
            }
        }

        val result = ConversationContextWindowBuilder.build(history)

        assertEquals(80, result.recentMessages.size)
        assertTrue(result.recentMessages.all { it.content.length == longReply.length || it.role == PromptRole.USER })
        assertTrue(result.recentMessages.sumOf { it.content.length } <= 70_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeWindowSize() {
        ConversationContextWindowBuilder.build(emptyList(), recentTurns = -1)
    }
}
