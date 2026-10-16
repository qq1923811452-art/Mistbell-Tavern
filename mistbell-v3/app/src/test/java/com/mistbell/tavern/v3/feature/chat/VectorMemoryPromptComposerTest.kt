package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.RetrievedMemory
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VectorMemoryPromptComposerTest {
    private val history = listOf(
        PromptMessage(PromptRole.SYSTEM, "Be helpful."),
        PromptMessage(PromptRole.USER, "Earlier question"),
        PromptMessage(PromptRole.ASSISTANT, "Earlier answer"),
        PromptMessage(PromptRole.USER, "What did I say?"),
    )

    @Test
    fun insertsMemorySectionImmediatelyBeforeCurrentUserMessage() {
        val result = VectorMemoryPromptComposer.compose(
            history,
            VectorMemoryContextResult.Ready(
                listOf(
                    RetrievedMemory("m1", "User is building Mistbell 3.0.", 0.91),
                    RetrievedMemory("m2", "User wants local embeddings.", 0.84),
                ),
            ),
        )

        assertEquals(5, result.size)
        assertEquals(PromptRole.SYSTEM, result[3].role)
        assertEquals(
            "Relevant local memories (use as contextual facts; current user instructions take precedence):\n" +
                "1. User is building Mistbell 3.0.\n" +
                "2. User wants local embeddings.",
            result[3].content,
        )
        assertEquals(history.last(), result.last())
        assertEquals(4, history.size)
    }

    @Test
    fun leavesPromptUnchangedWhenMemoryIsUnavailableOrEmpty() {
        assertSame(
            history,
            VectorMemoryPromptComposer.compose(
                history,
                VectorMemoryContextResult.NoRelevantMemories,
            ),
        )
        assertSame(
            history,
            VectorMemoryPromptComposer.compose(
                history,
                VectorMemoryContextResult.Unavailable(
                    VectorMemoryContextResult.Reason.SNAPSHOT_MISSING,
                ),
            ),
        )
    }

    @Test
    fun doesNotAddMemorySectionWithoutAUserMessage() {
        val systemOnly = listOf(PromptMessage(PromptRole.SYSTEM, "Be helpful."))
        assertSame(
            systemOnly,
            VectorMemoryPromptComposer.compose(
                systemOnly,
                VectorMemoryContextResult.Ready(
                    listOf(RetrievedMemory("m1", "A fact.", 0.9)),
                ),
            ),
        )
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

        assertEquals(5, result.size)
        assertEquals(
            "Relevant local memories (use as contextual facts; current user instructions take precedence):\n" +
                "1. A stable fact.",
            result[3].content,
        )
    }
}
