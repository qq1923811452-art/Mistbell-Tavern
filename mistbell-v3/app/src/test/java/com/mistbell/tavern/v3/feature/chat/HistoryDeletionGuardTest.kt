package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDeletionGuardTest {
    @Test
    fun concurrentDeletesDoNotClearEachOther() {
        val guard = HistoryDeletionGuard()
        guard.begin("a")
        guard.begin("b")
        guard.end("a")
        assertFalse(guard.isDeleting("a"))
        assertTrue(guard.isDeleting("b"))
        guard.end("b")
        assertFalse(guard.isDeleting("b"))
    }
}
