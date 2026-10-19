package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.RetrievedMemory
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMemoryPromptComposerTest {
    private val history = listOf(
        PromptMessage(PromptRole.SYSTEM, "Role card.\n\nActivated worldbook entries:\nWorld."),
        PromptMessage(PromptRole.USER, "Earlier question"),
        PromptMessage(PromptRole.ASSISTANT, "Earlier answer"),
        PromptMessage(PromptRole.USER, "What did I say?"),
    )

    @Test
    fun mergesMemoryIntoFirstSystemMessageWithoutAddingAnotherSystemMessage() {
        val result = VectorMemoryPromptComposer.compose(
            history,
            VectorMemoryContextResult.Ready(
                listOf(
                    RetrievedMemory("m1", "User is building Mistbell 3.0.", 0.91),
                    RetrievedMemory("m2", "User wants local embeddings.", 0.84),
                ),
            ),
        )
        assertEquals(4, result.size)
        assertEquals(1, result.count { it.role == PromptRole.SYSTEM })
        assertTrue(result[0].content.contains("User is building Mistbell 3.0."))
        assertTrue(result[0].content.indexOf("Activated worldbook entries") <
            result[0].content.indexOf(VectorMemoryPromptComposer.MEMORY_SECTION_HEADER))
        assertEquals(history.last(), result.last())
        assertEquals(4, history.size)
    }

    @Test
    fun leavesPromptUnchangedWhenMemoryIsUnavailableOrEmpty() {
        assertSame(history, VectorMemoryPromptComposer.compose(history, VectorMemoryContextResult.NoRelevantMemories))
        assertSame(
            history,
            VectorMemoryPromptComposer.compose(
                history,
                VectorMemoryContextResult.Unavailable(VectorMemoryContextResult.Reason.SNAPSHOT_MISSING),
            ),
        )
    }

    @Test
    fun leavesPromptUnchangedWhenThereIsNoSystemMessage() {
        val noSystem = listOf(PromptMessage(PromptRole.USER, "Hi"))
        assertSame(
            noSystem,
            VectorMemoryPromptComposer.compose(
                noSystem,
                VectorMemoryContextResult.Ready(listOf(RetrievedMemory("m1", "A fact.", 0.9))),
            ),
        )
    }

    @Test
    fun emptySystemMessageDoesNotCreateLeadingPromptSeparators() {
        val messages = listOf(PromptMessage(PromptRole.SYSTEM, ""), PromptMessage(PromptRole.USER, "Hi"))
        val result = VectorMemoryPromptComposer.compose(
            messages,
            VectorMemoryContextResult.Ready(listOf(RetrievedMemory("m1", "A stable fact.", 0.8))),
        )
        assertTrue(result.first().content.startsWith(VectorMemoryPromptComposer.MEMORY_SECTION_HEADER))
        assertTrue(!result.first().content.startsWith("\n\n"))
    }

    @Test
    fun skipsBlankOrInvalidScoreMemoryEntries() {
        val result = VectorMemoryPromptComposer.compose(
            history,
            VectorMemoryContextResult.Ready(
                listOf(
                    RetrievedMemory("blank", "  ", 0.9),
                    RetrievedMemory("invalid-score", "Must not be included.", Double.NaN),
                    RetrievedMemory("valid", "A stable fact.", 0.8),
                ),
            ),
        )
        assertEquals(4, result.size)
        assertEquals(1, result.count { it.role == PromptRole.SYSTEM })
        assertTrue(result[0].content.contains("A stable fact."))
        assertTrue(!result[0].content.contains("Must not be included."))
    }
}
