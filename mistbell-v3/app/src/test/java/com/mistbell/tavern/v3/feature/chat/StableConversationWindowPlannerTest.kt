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
        for (count in 1..TOTAL_TURNS) {
            val history = turns(count)
            val result = planner.plan(SCOPE, history, config)
            val expectedArchivedTurns = ((count - RECENT_TURNS).coerceAtLeast(0) / BLOCK_TURNS) * BLOCK_TURNS
            assertEquals(expectedArchivedTurns, result.archivedTurnCount)
            assertPartition(history, result)
            if (result.archivedTurnCount == previous.archivedTurnCount) {
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
        assertEquals(first.startId, before.startId)
        assertFalse(before.summaryRebuilt)
        val boundary = planner.plan(SCOPE, turns(RECENT_TURNS + BLOCK_TURNS), config)
        assertEquals("u5", boundary.startId)
        assertEquals("a4", boundary.summaryThroughId)
        assertEquals(BLOCK_TURNS, boundary.archivedTurnCount)
        val next = planner.plan(SCOPE, turns(RECENT_TURNS + BLOCK_TURNS * 2), config)
        assertEquals("u10", next.startId)
        assertEquals("a9", next.summaryThroughId)
    }

    @Test
    fun charactersWaitForHighWaterThenRemoveWholeBlocksBelowLowWater() {
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig(recentTurns = TURN_LIMIT, maxHistoryChars = LOW_WATER)
        val first = planner.plan(SCOPE, turns(BLOCK_TURNS - 1, TEXT_SIZE), config)
        val exact = planner.plan(SCOPE, turns(EXACT_HIGH_WATER_TURNS, TEXT_SIZE), config)
        assertEquals(first.startId, exact.startId)
        assertEquals(HIGH_WATER.toLong(), exact.window.recentHistoryChars)
        val evicted = planner.plan(SCOPE, turns(EXACT_HIGH_WATER_TURNS + 1, TEXT_SIZE), config)
        assertEquals(BLOCK_TURNS * 2, evicted.archivedTurnCount)
        assertTrue(evicted.window.recentHistoryChars < LOW_WATER)
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
        assertEquals(history.sumOf { it.content.length.toLong() }, result.window.recentHistoryChars)
        assertEquals("u0", result.startId)
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
        assertEquals("u10", initial.startId)
        assertEquals("a9", initial.summaryThroughId)
        val appended = turns(INITIAL_LONG_HISTORY + BLOCK_TURNS)
        val result = planner.plan(SCOPE, appended, config)
        assertEquals("u15", result.startId)
        assertNotEquals(initial.startId, result.startId)
        assertPartition(appended, result)
        val expectedSummary = ConversationContextWindowBuilder.build(
            appended.take(result.window.archivedMessageCount).map(WindowHistoryMessage::toPromptMessage),
            recentTurns = 0,
            summaryMaxChars = LARGE_BUDGET,
        ).earlierSummary
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
        assertEquals("u10", result.startId)
        assertEquals("a9extra", result.summaryThroughId)
        assertPartition(history, result)
    }

    private fun assertPartition(history: List<WindowHistoryMessage>, result: HistoryWindowDecision) {
        val split = result.window.archivedMessageCount
        assertEquals(history.drop(split).map(WindowHistoryMessage::toPromptMessage), result.window.recentMessages)
        assertEquals(history.getOrNull(split)?.id, result.startId)
        assertEquals(history.getOrNull(split - 1)?.id, result.summaryThroughId)
        assertEquals(history.take(split).count { it.role == PromptRole.USER }, result.archivedTurnCount)
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
private const val HIGH_WATER = 12_000
private const val EXACT_HIGH_WATER_TURNS = 12
private const val TEXT_SIZE = 500
private const val SMALL_BUDGET = 100
private const val LONG_TEXT_SIZE = 10_000
