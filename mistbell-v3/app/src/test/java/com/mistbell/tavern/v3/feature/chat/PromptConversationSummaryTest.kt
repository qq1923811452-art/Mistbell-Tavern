package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptConversationSummaryTest {
    @Test
    fun includesEarlierNotesInSystemMessageWithoutAddingTranscriptMessages() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                earlierConversationSummary = "用户：曾提到海边小镇；助手：记住了蓝色灯塔",
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "recent question"),
                    PromptMessage(PromptRole.ASSISTANT, "recent answer"),
                ),
                latestUserMessage = "latest question",
            ),
        )

        assertTrue(messages.first().content.contains("Earlier conversation notes (extractive, not a full transcript):\n"))
        assertTrue(messages.first().content.contains("蓝色灯塔"))
        assertTrue(messages.any { it.content == "recent question" })
        assertTrue(messages.none { it.content.contains("old transcript") })
    }
}
