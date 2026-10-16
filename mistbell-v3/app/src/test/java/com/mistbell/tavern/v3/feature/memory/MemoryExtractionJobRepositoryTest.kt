package com.mistbell.tavern.v3.feature.memory

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryExtractionJobRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedJobStoresOnlySafeFailureCategory() {
        val root = folder.newFolder("queue")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = root
        }
        val repository = MemoryExtractionJobRepository(MemoryExtractionJobStore(context))
        val job = MemoryExtractionJob(
            id = "job-1",
            conversationId = "conversation-1",
            turnIds = listOf("turn-1"),
            providerProfileId = "memory",
            credentialHandle = "opaque-handle",
            createdAtEpochMillis = 100L,
        )
        assertTrue(repository.enqueue(job))
        repository.claimDue(100L)
        assertTrue(repository.fail("job-1", 200L, "secret-token-should-not-persist"))
        assertEquals("Extraction attempt failed.", repository.list().single().lastSafeError)
    }
}
