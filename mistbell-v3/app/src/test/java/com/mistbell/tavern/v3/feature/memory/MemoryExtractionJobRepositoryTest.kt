package com.mistbell.tavern.v3.feature.memory

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

    @Test fun enqueueIsIdempotentForSameJobAndRejectsIdCollision() {
        val repository = repository()
        val original = job("same-id", createdAt = 100L)
        assertTrue(repository.enqueue(original))
        org.junit.Assert.assertFalse(repository.enqueue(original))
        val conflicting = original.copy(conversationId = "different-conversation")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            repository.enqueue(conflicting)
        }
        assertEquals(1, repository.list().size)
    }

    @Test fun interruptedRunningJobReturnsToDueRetryWait() {
        val repository = repository()
        assertTrue(repository.enqueue(job("interrupted", createdAt = 100L)))
        assertEquals("interrupted", repository.claimDue(100L)?.id)

        assertEquals(1, repository.recoverInterrupted(500L))

        val recovered = repository.list().single()
        assertEquals(MemoryExtractionJob.State.RETRY_WAIT, recovered.state)
        assertEquals(500L, recovered.nextAttemptAtEpochMillis)
        assertEquals("Previous attempt was interrupted.", recovered.lastSafeError)
        assertEquals("interrupted", repository.claimDue(500L)?.id)
    }

    @Test fun retryDelayGrowsExponentiallyAndSaturatesTimestampOnOverflow() {
        val repository = repository()
        assertTrue(repository.enqueue(job("retry", createdAt = 1_000L)))
        repository.claimDue(1_000L)
        repository.fail("retry", 2_000L, "network")
        assertEquals(32_000L, repository.list().single().nextAttemptAtEpochMillis)

        repository.claimDue(32_000L)
        repository.fail("retry", 40_000L, "timeout")
        assertEquals(100_000L, repository.list().single().nextAttemptAtEpochMillis)

        val overflowRepository = repository()
        val overflowJob = job(
            id = "overflow",
            createdAt = 1L,
            attemptCount = MemoryExtractionJob.MAX_ATTEMPTS - 1,
            state = MemoryExtractionJob.State.RUNNING,
        )
        assertTrue(overflowRepository.enqueue(overflowJob))
        assertTrue(overflowRepository.fail("overflow", Long.MAX_VALUE - 1L, "network"))
        assertEquals(Long.MAX_VALUE, overflowRepository.list().single().nextAttemptAtEpochMillis)
    }
    @Test fun laterBatchInSameConversationWaitsForEarlierRetry() {
        val repository = repository()
        val earlier = job("earlier", createdAt = 100L).copy(
            attemptCount = 1,
            state = MemoryExtractionJob.State.RETRY_WAIT,
            nextAttemptAtEpochMillis = 2_000L,
        )
        val later = job("later", createdAt = 200L)
        assertTrue(repository.enqueue(earlier))
        assertTrue(repository.enqueue(later))

        assertEquals(null, repository.claimDue(1_000L))
        assertEquals(
            MemoryExtractionJob.State.PENDING,
            repository.list().first { it.id == "later" }.state,
        )
        assertEquals("earlier", repository.claimDue(2_000L)?.id)
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
        return MemoryExtractionJobRepository(MemoryExtractionJobStore(root))
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
