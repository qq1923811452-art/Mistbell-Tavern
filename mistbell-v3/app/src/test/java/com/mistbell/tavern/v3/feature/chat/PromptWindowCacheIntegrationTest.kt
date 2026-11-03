package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptWindowCacheIntegrationTest {
    @Test
    fun repeatedEvictionsKeepARealHistoryPrefixAndBoundTotalRawTurns() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(maxHistoryChars = 100_000)
        var previous = emptyList<PromptMessage>()
        var evictions = 0
        for (turn in 1..200) {
            val source = (0 until turn).flatMap { index ->
                listOf(
                    WindowHistoryMessage("u$index", PromptRole.USER, "user $index"),
                    WindowHistoryMessage("a$index", PromptRole.ASSISTANT, "reply $index"),
                )
            }
            val result = planner.plan("scope", source, config)
            val window = result.window
            val prompt = ChatPromptAssemblyCoordinator().assemble(
                characterDefinition = "Character", contextWindow = window,
                activatedWorldbook = emptyList(), latestUserMessage = "continue $turn",
            )
            val pins = window.pinnedPrefixMessages
            if (window.earlierSummary.isNotEmpty()) {
                evictions++
                assertTrue(pins.isNotEmpty())
                assertEquals(previous.take(1 + pins.size), prompt.take(1 + pins.size))
                assertTrue(window.recentMessages.last().content == "reply ${turn - 1}")
            }
            val raw = pins + window.recentMessages
            assertTrue(raw.count { it.role == PromptRole.USER } <= config.recentTurns + config.blockTurns - 1)
            assertEquals(raw.size, raw.map { it.content }.distinct().size)
            previous = prompt
        }
        assertTrue(evictions > 10)
    }
}
