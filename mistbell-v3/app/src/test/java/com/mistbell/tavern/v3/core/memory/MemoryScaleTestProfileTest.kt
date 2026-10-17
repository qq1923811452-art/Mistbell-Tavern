package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScaleTestProfileTest {
    @Test
    fun exposesThreeIncreasingBenchmarkScales() {
        assertEquals(
            listOf(10_000, 50_000, 100_000),
            MemoryScaleTestProfile.entries.map(MemoryScaleTestProfile::recordCount),
        )
    }

    @Test
    fun resolvesOnlySupportedProfileSizes() {
        assertEquals(MemoryScaleTestProfile.BASELINE, MemoryScaleTestProfile.fromRecordCount(10_000))
        assertEquals(MemoryScaleTestProfile.MEDIUM, MemoryScaleTestProfile.fromRecordCount(50_000))
        assertEquals(MemoryScaleTestProfile.LARGE, MemoryScaleTestProfile.fromRecordCount(100_000))
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
