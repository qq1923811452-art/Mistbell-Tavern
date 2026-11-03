package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableConversationWindowPlannerTest {
    @Test
    fun twoHundredAppendsRetainAnchorsAndSummaryBetweenFiveTurnBlocks() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(maxHistoryChars = LARGE_BUDGET)
        var previous = planner.plan(SCOPE, turns(0), config)
        var pinnedIds = emptyList<String>()
        for (count in 1..TOTAL_TURNS) {
            val history = turns(count)
            val result = planner.plan(SCOPE, history, config)
            if (pinnedIds.isEmpty() && result.window.pinnedPrefixMessages.isNotEmpty()) {
                pinnedIds = result.window.pinnedPrefixMessages.mapIndexed { index, _ -> history[index].id }
            }
            assertEquals(pinnedIds, result.window.pinnedPrefixMessages.mapIndexed { index, _ -> history[index].id })
            assertPartition(history, result)
            assertTrue(result.window.retainedHistoryChars <= config.maxHistoryChars + HIGH_WATER_TEST_ALLOWANCE)
            assertTrue(result.window.pinnedPrefixMessages.count { it.role == PromptRole.USER } <= MAX_TEST_PIN_TURNS)
            assertTrue(result.window.pinnedPrefixMessages.count { it.role == PromptRole.USER } +
                result.window.recentMessages.count { it.role == PromptRole.USER } <= RECENT_TURNS + BLOCK_TURNS)
            if (result.window.archivedMessageCount == previous.window.archivedMessageCount) {
                assertEquals(previous.window.earlierSummary, result.window.earlierSummary)
                assertFalse(result.summaryRebuilt)
                if (count > 1) assertEquals(previous.startId, result.startId)
            } else {
                assertTrue(result.summaryRebuilt)
            }
            val retry = planner.plan(SCOPE, history, config)
            assertEquals(result.window, retry.window)
            assertFalse(retry.summaryRebuilt)
            previous = result
        }
    }

    @Test
    fun fortyTurnsOnlyMoveAtFortyFiveAndThenFifty() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(maxHistoryChars = LARGE_BUDGET)
        val first = planner.plan(SCOPE, turns(RECENT_TURNS), config)
        val before = planner.plan(SCOPE, turns(RECENT_TURNS + BLOCK_TURNS - 1), config)
        assertEquals(first.window.pinnedPrefixMessages, before.window.pinnedPrefixMessages)
        assertFalse(before.summaryRebuilt)
        val boundary = planner.plan(SCOPE, turns(RECENT_TURNS + BLOCK_TURNS), config)
        assertEquals(first.window.pinnedPrefixMessages, boundary.window.pinnedPrefixMessages)
        assertTrue(boundary.window.archivedMessageCount >= boundary.window.pinnedPrefixMessages.size)
        val next = planner.plan(SCOPE, turns(RECENT_TURNS + BLOCK_TURNS * 2), config)
        assertEquals(boundary.window.pinnedPrefixMessages, next.window.pinnedPrefixMessages)
        assertTrue(next.window.archivedMessageCount >= boundary.window.archivedMessageCount)
    }

    @Test
    fun charactersWaitForHighWaterThenRemoveWholeBlocksBelowLowWater() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(recentTurns = TURN_LIMIT, maxHistoryChars = LOW_WATER)
        val first = planner.plan(SCOPE, turns(BLOCK_TURNS - 1, TEXT_SIZE), config)
        val exact = planner.plan(SCOPE, turns(EXACT_HIGH_WATER_TURNS, TEXT_SIZE), config)
        assertEquals(first.window.pinnedPrefixMessages, exact.window.pinnedPrefixMessages)
        val evicted = planner.plan(SCOPE, turns(EXACT_HIGH_WATER_TURNS + 1, TEXT_SIZE), config)
        assertEquals(exact.window.pinnedPrefixMessages, evicted.window.pinnedPrefixMessages)
        assertTrue(evicted.window.retainedHistoryChars <= config.maxHistoryChars.toLong())
        assertPartition(turns(EXACT_HIGH_WATER_TURNS + 1, TEXT_SIZE), evicted)
    }

    @Test
    fun indivisibleLatestTurnAndIncompleteBlockCanExceedSoftUtf16Budget() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(maxHistoryChars = SMALL_BUDGET)
        planner.plan(SCOPE, turns(1), config)
        val history = turns(2).toMutableList()
        history[history.lastIndex] = history.last().copy(content = "😀".repeat(LONG_TEXT_SIZE))
        val result = planner.plan(SCOPE, history, config)
        assertTrue(result.window.isOverHistoryBudget)
        assertEquals(history.last().content, result.window.recentMessages.last().content)
        assertEquals(history.sumOf { it.content.length.toLong() } - result.window.pinnedPrefixChars,
            result.window.recentHistoryChars)
        assertEquals("u1", result.startId)
        assertPartition(history, result)
    }

    @Test
    fun identityRoleContentDeletionAndVariantsInvalidateSnapshots() {
        val history = turns(INITIAL_LONG_HISTORY)
        val mutations = listOf(
            history.dropLast(1),
            history.toMutableList().apply { this[0] = first().copy(id = "replacement") },
            history.toMutableList().apply { this[0] = first().copy(role = PromptRole.ASSISTANT) },
            history.toMutableList().apply { this[0] = first().copy(content = "edited") },
            history.toMutableList().apply { this[0] = first().copy(sourceRole = "changed-source-role") },
            history.toMutableList().apply { this[1] = this[1].copy(variants = listOf("same", "same")) },
            history.toMutableList().apply { this[1] = this[1].copy(activeVariant = 1) },
        )
        for (mutation in mutations) {
            val planner = StableConversationWindowPlanner()
            val config = HistoryWindowConfig(maxHistoryChars = LARGE_BUDGET)
            planner.plan(SCOPE, history, config)
            val result = planner.plan(SCOPE, mutation, config)
            assertEquals("source-changed", result.reason)
            assertTrue(result.summaryRebuilt)
            assertPartition(mutation, result)
        }
    }

    @Test
    fun scopeConfigurationAndExplicitRegenerationInvalidate() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(maxHistoryChars = LARGE_BUDGET)
        val history = turns(INITIAL_LONG_HISTORY)
        planner.plan(SCOPE, history, config)
        assertEquals("scope-changed", planner.plan("other", history, config).reason)
        val configs = listOf(
            config.copy(recentTurns = 1), config.copy(summaryMaxChars = 0),
            config.copy(maxHistoryChars = SMALL_BUDGET), config.copy(blockTurns = 1),
        )
        for (changed in configs) {
            assertEquals("config-changed", planner.plan("other", history, changed).reason)
        }
        assertEquals(
            "explicit-invalidation",
            planner.plan("other", history, configs.last(), invalidate = true).reason,
        )
    }

    @Test
    fun repeatedTextUsesExactIdsAndSummaryCoversTheContiguousOlderPrefix() {
        val planner = StableConversationWindowPlanner()
        val history = turns(INITIAL_LONG_HISTORY)
        val config = HistoryWindowConfig(summaryMaxChars = LARGE_BUDGET, maxHistoryChars = LARGE_BUDGET)
        val initial = planner.plan(SCOPE, history, config)
        assertTrue(initial.window.pinnedPrefixMessages.isNotEmpty())
        val appended = turns(INITIAL_LONG_HISTORY + BLOCK_TURNS)
        val result = planner.plan(SCOPE, appended, config)
        assertEquals(initial.window.pinnedPrefixMessages, result.window.pinnedPrefixMessages)
        assertPartition(appended, result)
        val pinCount = result.window.pinnedPrefixMessages.size
        val expectedSummary = ConversationContextWindowBuilder.summarizeOlder(
            appended.subList(pinCount, result.window.archivedMessageCount)
                .map(WindowHistoryMessage::toPromptMessage),
            config.summaryMaxChars,
        )
        assertEquals(expectedSummary, result.window.earlierSummary)
    }

    @Test
    fun openingAndMultipleAssistantsStayOnOneFinalBoundary() {
        val history = listOf(WindowHistoryMessage("opening", PromptRole.ASSISTANT, "opening")) +
            turns(INITIAL_LONG_HISTORY).flatMap { message ->
                if (message.role == PromptRole.ASSISTANT) {
                    listOf(message, message.copy(id = message.id + "extra"))
                } else {
                    listOf(message)
                }
            }
        val result = StableConversationWindowPlanner().plan(SCOPE, history, HistoryWindowConfig())
        assertTrue(result.window.pinnedPrefixMessages.isNotEmpty())
        assertPartition(history, result)
    }

    private fun assertPartition(history: List<WindowHistoryMessage>, result: HistoryWindowDecision) {
        val split = result.window.archivedMessageCount
        val pinCount = result.window.pinnedPrefixMessages.size
        assertEquals(
            history.take(pinCount).map(WindowHistoryMessage::toPromptMessage),
            result.window.pinnedPrefixMessages,
        )
        assertEquals(history.drop(split).map(WindowHistoryMessage::toPromptMessage), result.window.recentMessages)
        assertEquals(history.getOrNull(split)?.id, result.startId)
        assertEquals(history.getOrNull(split - 1)?.id, result.summaryThroughId)
        assertEquals(
            history.subList(pinCount.coerceAtMost(split), split).count { it.role == PromptRole.USER },
            result.archivedTurnCount,
        )
    }

    @Test
    fun growingPinFreezesBeforeAssistantCompletionAndHandlesOpeningAssistant() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(recentTurns = 8, maxHistoryChars = 1_000)
        val early = turns(1, 300)
        val first = planner.plan(SCOPE, early, config)
        assertTrue(first.window.pinnedPrefixMessages.isEmpty())
        val partial = early + WindowHistoryMessage("u1", PromptRole.USER, "next")
        val grown = planner.plan(SCOPE, partial, config)
        val pin = grown.window.pinnedPrefixMessages
        val completed = partial + WindowHistoryMessage("a1", PromptRole.ASSISTANT, "reply")
        val afterAssistant = planner.plan(SCOPE, completed, config)
        assertEquals(pin, afterAssistant.window.pinnedPrefixMessages)

        val opening = listOf(WindowHistoryMessage("opening", PromptRole.ASSISTANT, "intro")) + turns(10)
        val openingResult = StableConversationWindowPlanner().plan(SCOPE, opening, config)
        assertTrue(openingResult.window.pinnedPrefixChars <= config.maxHistoryChars / 4)
        assertTrue(openingResult.window.pinnedPrefixMessages.first().content == "intro")
    }

    private fun turns(count: Int, chars: Int = 1): List<WindowHistoryMessage> = (0 until count).flatMap { turn ->
        listOf(
            WindowHistoryMessage("u$turn", PromptRole.USER, "x".repeat(chars)),
            WindowHistoryMessage("a$turn", PromptRole.ASSISTANT, "x".repeat(chars)),
        )
    }
}

private const val SCOPE = "session"
private const val TOTAL_TURNS = 200
private const val RECENT_TURNS = 40
private const val BLOCK_TURNS = 5
private const val INITIAL_LONG_HISTORY = 50
private const val LARGE_BUDGET = 100_000
private const val TURN_LIMIT = 100
private const val LOW_WATER = 4_000
private const val EXACT_HIGH_WATER_TURNS = 12
private const val TEXT_SIZE = 500
private const val SMALL_BUDGET = 100
private const val LONG_TEXT_SIZE = 10_000
private const val HIGH_WATER_TEST_ALLOWANCE = 8_000
private const val MAX_TEST_PIN_TURNS = 10
