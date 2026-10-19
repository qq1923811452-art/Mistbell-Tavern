package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextWindowTest {
    @Test
    fun keepsOnlyNewestTwentyTurnsByDefaultAndSummarizesOlderMessages() {
        val history = (1..50).map { index ->
            PromptMessage(
                if (index % 2 == 1) PromptRole.USER else PromptRole.ASSISTANT,
                "message-$index",
            )
        }

        val result = ConversationContextWindowBuilder.build(history)

        assertEquals(40, result.recentMessages.size)
        assertEquals("message-11", result.recentMessages.first().content)
        assertEquals("message-50", result.recentMessages.last().content)
        assertTrue(result.earlierSummary.contains("message-10"))
        assertTrue(result.earlierSummary.length <= ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS)
        assertTrue(!result.earlierSummary.contains("message-11"))
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeWindowSize() {
        ConversationContextWindowBuilder.build(emptyList(), recentTurns = -1)
    }
}
