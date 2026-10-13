package com.mistbell.tavern.android.data.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MemoryRankingTest {
    private val now = Instant.parse("2026-10-13T00:00:00Z")

    @Test
    fun `durable profile memories do not decay with age`() {
        val recent = memory(type = "identity", updatedAt = now.toString())
        val old = memory(type = "identity", updatedAt = "2020-01-01T00:00:00Z")

        assertEquals(MemoryRanking.score(recent, now), MemoryRanking.score(old, now), 0.0001)
    }

    @Test
    fun `episodic memories lose priority as they age`() {
        val recent = memory(type = "event", updatedAt = now.toString())
        val old = memory(type = "event", updatedAt = "2025-10-13T00:00:00Z")

        assertTrue(MemoryRanking.score(recent, now) > MemoryRanking.score(old, now))
    }

    @Test
    fun `access reinforcement is bounded and never changes stored importance`() {
        val untouched = memory(type = "note", updatedAt = now.toString(), accessCount = 0)
        val frequentlyUsed = untouched.copy(accessCount = 100_000)

        assertTrue(MemoryRanking.score(frequentlyUsed, now) > MemoryRanking.score(untouched, now))
        assertEquals(5, frequentlyUsed.importance)
    }

    private fun memory(
        type: String,
        updatedAt: String,
        accessCount: Int = 0,
    ) = StructuredMemory(
        ownerId = "owner",
        characterId = "character",
        sessionId = null,
        memoryType = type,
        title = null,
        content = "memory content",
        structuredData = null,
        importance = 5,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastAccessedAt = null,
        accessCount = accessCount,
    )
}
