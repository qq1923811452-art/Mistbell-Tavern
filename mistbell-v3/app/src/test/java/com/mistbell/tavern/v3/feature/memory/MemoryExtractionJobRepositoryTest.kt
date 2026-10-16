package com.mistbell.tavern.v3.feature.memory

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryExtractionJobRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedJobStoresOnlySafeFailureCategory() {
        val repository = repository()
        val job = job("job-1", createdAt = 100L)
        assertTrue(repository.enqueue(job))
        repository.claimDue(100L)
        assertTrue(repository.fail("job-1", 200L, "secret-token-should-not-persist"))
        assertEquals("Extraction attempt failed.", repository.list().single().lastSafeError)
    }

    @Test fun exhaustedDueJobDoesNotBlockAnotherDueJob() {
        val repository = repository()
        val exhausted = job(
            id = "exhausted",
            createdAt = 10L,
            attemptCount = MemoryExtractionJob.MAX_ATTEMPTS,
            state = MemoryExtractionJob.State.RETRY_WAIT,
        )
        val ready = job(id = "ready", createdAt = 20L)
        assertTrue(repository.enqueue(exhausted))
        assertTrue(repository.enqueue(ready))

        val claimed = repository.claimDue(1_000L)

        assertNotNull(claimed)
        assertEquals("ready", claimed?.id)
        val byId = repository.list().associateBy(MemoryExtractionJob::id)
        assertEquals(MemoryExtractionJob.State.PERMANENT_FAILURE, byId.getValue("exhausted").state)
        assertEquals(MemoryExtractionJob.State.RUNNING, byId.getValue("ready").state)
        assertEquals("Retry limit reached.", byId.getValue("exhausted").lastSafeError)
    }

    private fun repository(): MemoryExtractionJobRepository {
        val root = folder.newFolder()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = root
        }
        return MemoryExtractionJobRepository(MemoryExtractionJobStore(context))
    }

    private fun job(
        id: String,
        createdAt: Long,
        attemptCount: Int = 0,
        state: MemoryExtractionJob.State = MemoryExtractionJob.State.PENDING,
    ) = MemoryExtractionJob(
        id = id,
        conversationId = "conversation-1",
        turnIds = listOf("turn-$id"),
        providerProfileId = "memory",
        credentialHandle = "opaque-handle",
        createdAtEpochMillis = createdAt,
        attemptCount = attemptCount,
        nextAttemptAtEpochMillis = createdAt,
        state = state,
    )
}
