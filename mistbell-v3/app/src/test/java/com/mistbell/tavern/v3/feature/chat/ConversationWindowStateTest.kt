package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWindowStateTest {
    @Test
    fun restartRestoresStableFortyFourTurnAnchorAndAllowsAppend() {
        val planner = StableConversationWindowPlanner()
        planner.plan(SCOPE, history(40), CONFIG)
        val before = planner.plan(SCOPE, history(44), CONFIG)
        val state = checkNotNull(planner.exportState())
        val restarted = StableConversationWindowPlanner()
        assertTrue(restarted.restore(state, SCOPE, history(45), CONFIG))
        val result = restarted.plan(SCOPE, history(45), CONFIG)
        assertEquals("u5", result.startId)
        assertEquals(5, result.archivedTurnCount)
        assertEquals("u0", before.startId)
        assertEquals(planner.plan(SCOPE, history(45), CONFIG).window, result.window)
    }

    @Test
    fun summaryWithoutArchivedMessagesIsRejected() {
        val source = history(4)
        val planner = StableConversationWindowPlanner()
        planner.plan(SCOPE, source, CONFIG)
        val valid = checkNotNull(planner.exportState())
        val inconsistent = valid.copy(
            archivedMessageCount = 0,
            startId = source.first().id,
            summaryThroughId = null,
            summary = "stale summary",
        )
        assertFalse(inconsistent.matches(SCOPE, source, CONFIG))
        assertTrue(valid.matches(SCOPE, source, CONFIG))
    }

    @Test
    fun everyFieldWhitespaceVariantsAndFramingChangeFingerprint() {
        val original = WindowHistoryMessage("id", PromptRole.USER, " text ", listOf("a", "b"))
        val hash = WindowSourceFingerprint.hash(listOf(original))
        val changes = listOf(
            original.copy(id = "other"), original.copy(role = PromptRole.ASSISTANT),
            original.copy(content = "text"), original.copy(variants = listOf("b", "a")),
            original.copy(activeVariant = 1), original.copy(sourceRole = "changed"),
            original.copy(variants = listOf("ab")), original.copy(content = " "),
        )
        changes.forEach { assertNotEquals(hash, WindowSourceFingerprint.hash(listOf(it))) }
        assertNotEquals(WindowSourceFingerprint.hash(emptyList()), WindowSourceFingerprint.hash(listOf(original)))
        assertNotEquals(
            WindowSourceFingerprint.hash(listOf(original.copy(id = "ab", content = "c"))),
            WindowSourceFingerprint.hash(listOf(original.copy(id = "a", content = "bc"))),
        )
    }

    @Test
    fun staleStateCannotRestoreEditedDeletedOrBlankSource() {
        val planner = StableConversationWindowPlanner()
        val source = history(44) + WindowHistoryMessage("blank", PromptRole.ASSISTANT, " ")
        planner.plan(SCOPE, source, CONFIG)
        val state = checkNotNull(planner.exportState())
        val edited = source.toMutableList().apply { this[0] = first().copy(content = "edit") }
        val blankEdited = source.dropLast(1) + source.last().copy(content = "  ")
        for (changed in listOf(edited, blankEdited, source.dropLast(1))) {
            assertFalse(StableConversationWindowPlanner().restore(state, SCOPE, changed, CONFIG))
        }
        assertFalse(state.matches("other", source, CONFIG))
        assertFalse(state.matches(SCOPE, source, CONFIG.copy(recentTurns = 1)))
    }

    @Test
    fun corruptVersionBoundaryCountsAndOversizeSummaryAreRejectedWithoutTruncation() {
        val planner = StableConversationWindowPlanner()
        val source = history(50)
        planner.plan(SCOPE, source, CONFIG)
        val state = checkNotNull(planner.exportState())
        val invalid = listOf(
            state.copy(version = 0), state.copy(sourceCount = -1), state.copy(archivedMessageCount = -1),
            state.copy(startId = "wrong"), state.copy(summaryThroughId = "wrong"),
            state.copy(
                archivedMessageCount = state.archivedMessageCount + 1,
                startId = "a10", summaryThroughId = "u10",
            ),
            state.copy(summary = "x".repeat(CONFIG.summaryMaxChars + 1)),
        )
        invalid.forEach { assertFalse(it.matches(SCOPE, source, CONFIG)) }
        val restart = StableConversationWindowPlanner()
        assertTrue(restart.restore(state, SCOPE, source, CONFIG))
        assertEquals(state.summary, restart.plan(SCOPE, source, CONFIG).window.earlierSummary)
        assertFalse(restart.restore(state, SCOPE, source, CONFIG))
    }

    private fun history(turns: Int) = (0 until turns).flatMap {
        listOf(
            WindowHistoryMessage("u$it", PromptRole.USER, "same"),
            WindowHistoryMessage("a$it", PromptRole.ASSISTANT, "same"),
        )
    }
}

private const val SCOPE = "session"
private val CONFIG = HistoryWindowConfig(maxHistoryChars = 100_000)
