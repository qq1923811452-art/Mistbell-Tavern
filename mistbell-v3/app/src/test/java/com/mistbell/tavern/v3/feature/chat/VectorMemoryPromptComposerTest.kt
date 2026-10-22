package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.RetrievedMemory
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMemoryPromptComposerTest {
    private val history = listOf(
        PromptMessage(PromptRole.SYSTEM, "Role card.\n\nWorldbook constants:\nWorld."),
        PromptMessage(PromptRole.USER, "Earlier question"),
        PromptMessage(PromptRole.ASSISTANT, "Earlier answer"),
        PromptMessage(PromptRole.USER, "What did I say?"),
    )

    @Test
    fun appendsMemoryToTheLastUserMessageAndLeavesSystemUntouched() {
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
        assertEquals(history[0], result[0])
        val tail = result.last()
        assertEquals(PromptRole.USER, tail.role)
        assertTrue(tail.content.contains("User is building Mistbell 3.0."))
        assertTrue(tail.content.contains("What did I say?"))
        assertTrue(tail.content.indexOf(VectorMemoryPromptComposer.MEMORY_SECTION_HEADER) <
            tail.content.indexOf("What did I say?"))
        assertEquals(history, listOf(history[0], history[1], history[2], history[3]))
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
    fun appendsMemoryEvenWithoutSystemMessage() {
        val noSystem = listOf(PromptMessage(PromptRole.USER, "Hi"))
        val result = VectorMemoryPromptComposer.compose(
            noSystem,
            VectorMemoryContextResult.Ready(listOf(RetrievedMemory("m1", "A fact.", 0.9))),
        )
        assertEquals(1, result.size)
        assertTrue(result.single().content.startsWith(VectorMemoryPromptComposer.MEMORY_SECTION_HEADER))
        assertTrue(result.single().content.endsWith("Hi"))
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
        assertTrue(result.last().content.contains("A stable fact."))
        assertTrue(!result.last().content.contains("Must not be included."))
        assertEquals(history[0], result[0])
    }
}
