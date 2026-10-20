package com.mistbell.tavern.v3.feature.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryExtractionJobStateTest {
    @Test fun retryLimitsAreBounded() {
        assertEquals(5, MemoryExtractionJob.MAX_ATTEMPTS)
        assertEquals(512, MemoryExtractionJob.MAX_ERROR_CHARS)
    }
}
