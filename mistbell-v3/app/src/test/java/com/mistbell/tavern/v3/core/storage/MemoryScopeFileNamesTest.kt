package com.mistbell.tavern.v3.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScopeFileNamesTest {
    @Test
    fun scopeFilesAreStableDistinctAndRemainSimpleFileNames() {
        val first = MemoryScopeFileNames.forScope("mistbell-v3-vectors.bin", "conversation-A")
        val same = MemoryScopeFileNames.forScope("mistbell-v3-vectors.bin", "conversation-A")
        val second = MemoryScopeFileNames.forScope("mistbell-v3-vectors.bin", "conversation-B")

        assertEquals(first, same)
        assertNotEquals(first, second)
        assertTrue('/' !in first && '\\' !in first)
        assertTrue(first.endsWith(".bin"))
    }
}
