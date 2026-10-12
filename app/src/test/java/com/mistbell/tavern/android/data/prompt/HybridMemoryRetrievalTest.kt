package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.data.api.Usage
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.vector.VectorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMemoryRetrievalTest {
    @Test
    fun `semantic and lexical sources are fused and duplicate facts are shown once`() {
        val vector = VectorStore.SearchResult(
            id = "vector-1",
            score = 0.9f,
            content = "Assistant: The user prefers concise replies",
            metadata = emptyMap(),
        )
        val lexical = message("The user prefers concise replies")

        val result = PromptBuilder.buildHybridMemoryContextForPrompt(
            vectorResults = listOf(vector),
            lexicalItems = listOf(lexical),
            alreadyInjected = emptyList(),
        )

        assertTrue(result.contains("语义+关键词"))
        assertEquals(1, Regex("The user prefers concise replies").findAll(result).count())
    }

    @Test
    fun `structured facts already injected are excluded from hybrid history`() {
        val vector = VectorStore.SearchResult(
            id = "vector-1",
            score = 0.9f,
            content = "The user prefers concise replies",
            metadata = emptyMap(),
        )
        val result = PromptBuilder.buildHybridMemoryContextForPrompt(
            vectorResults = listOf(vector),
            lexicalItems = emptyList(),
            alreadyInjected = listOf("The user prefers concise replies"),
        )

        assertEquals("", result)
    }

    @Test
    fun `non-positive limit returns no prompt block`() {
        val result = PromptBuilder.buildHybridMemoryContextForPrompt(
            vectorResults = emptyList(),
            lexicalItems = listOf(message("A relevant past event")),
            alreadyInjected = emptyList(),
            limit = 0,
        )

        assertFalse(result.isNotEmpty())
    }

    private fun message(content: String) =
        MessageEntity(
            id = "message-1",
            sessionId = "session-1",
            ownerId = "owner-1",
            characterId = "character-1",
            role = "assistant",
            content = content,
            thinking = null,
            createdAt = "2026-10-12T10:00:00Z",
            memoryIdsJson = "[]",
            swipesJson = "[]",
            swipeIndex = 0,
            thinkingSwipesJson = "[]",
        )
}
