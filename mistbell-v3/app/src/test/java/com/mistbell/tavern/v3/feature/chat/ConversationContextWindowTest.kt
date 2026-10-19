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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeWindowSize() {
        ConversationContextWindowBuilder.build(emptyList(), recentTurns = -1)
    }
}
