package com.mistbell.tavern.android.data.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConflictResolverTest {
    @Test
    fun `same subject and relation with changed value supersedes prior fact`() {
        val old = memory("""{"subject":"user","relation":"name","object":"Alex"}""")
        val conflicts = MemoryConflictResolver.findSuperseded("user", "name", "Jordan", listOf(old))

        assertEquals(listOf(old.id), conflicts.map { it.id })
        val supersededAt = "2026-10-13T12:00:00Z"
        val historical = MemoryConflictResolver.markSuperseded(old, "user 的名字是 Jordan", supersededAt)
        assertTrue(MemoryConflictResolver.isSuperseded(historical))
        assertTrue(historical.structuredData!!.contains("Alex"))
        assertTrue(historical.structuredData!!.contains("Jordan"))
        assertEquals(old.updatedAt, historical.updatedAt)
        assertTrue(historical.structuredData!!.contains(supersededAt))
    }

    @Test
    fun `same value is not treated as a contradiction`() {
        val old = memory("""{"subject":"user","relation":"name","object":"Alex"}""")
        assertTrue(MemoryConflictResolver.findSuperseded("user", "name", "Alex", listOf(old)).isEmpty())
    }

    @Test
    fun `legacy or malformed metadata remains active and safe`() {
        assertFalse(MemoryConflictResolver.isSuperseded(memory(null)))
        assertFalse(MemoryConflictResolver.isSuperseded(memory("not-json")))
    }

    @Test
    fun `non primitive status and fact fields are ignored safely`() {
        val malformed = memory(
            """{"status":{"value":"superseded"},"subject":[],"relation":{},"object":true}""",
        )

        assertFalse(MemoryConflictResolver.isSuperseded(malformed))
        assertTrue(
            MemoryConflictResolver.findSuperseded("user", "name", "Jordan", listOf(malformed)).isEmpty(),
        )
    }

    @Test
    fun `already superseded facts are not superseded repeatedly`() {
        val old =
            MemoryConflictResolver.markSuperseded(
                memory("""{"subject":"user","relation":"name","object":"Alex"}"""),
                "user 的名字是 Jordan",
            )
        assertTrue(MemoryConflictResolver.findSuperseded("user", "name", "Taylor", listOf(old)).isEmpty())
    }

    private fun memory(structuredData: String?) =
        StructuredMemory(
            id = 12,
            ownerId = "owner",
            characterId = "character",
            sessionId = "session",
            memoryType = "identity",
            title = "Name",
            content = "user 的名字是 Alex",
            structuredData = structuredData,
            importance = 8,
            createdAt = "2026-10-01T00:00:00Z",
            updatedAt = "2026-10-01T00:00:00Z",
            lastAccessedAt = null,
        )
}
