package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScaleTestProfileTest {
    @Test
    fun exposesThreeIncreasingBenchmarkScales() {
        assertEquals(
            listOf(1_000, 5_000, 10_000),
            MemoryScaleTestProfile.entries.map(MemoryScaleTestProfile::recordCount),
        )
    }

    @Test
    fun resolvesOnlySupportedProfileSizes() {
        assertEquals(MemoryScaleTestProfile.SMALL, MemoryScaleTestProfile.fromRecordCount(1_000))
        assertEquals(MemoryScaleTestProfile.MEDIUM, MemoryScaleTestProfile.fromRecordCount(5_000))
        assertEquals(MemoryScaleTestProfile.LARGE, MemoryScaleTestProfile.fromRecordCount(10_000))
        assertNull(MemoryScaleTestProfile.fromRecordCount(25_000))
    }

    @Test
    fun everyProfileExplainsItsPurpose() {
        MemoryScaleTestProfile.entries.forEach { profile ->
            assertTrue(profile.label.isNotBlank())
            assertTrue(profile.purpose.isNotBlank())
        }
    }
}
