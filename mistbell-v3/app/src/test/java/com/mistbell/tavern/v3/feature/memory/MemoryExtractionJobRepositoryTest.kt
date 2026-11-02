package com.mistbell.tavern.v3.feature.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MemoryExtractionJobRepositoryTest {
    @Test fun failedJobStoresOnlySafeFailureCategory() {
        val repository = repository()
        val job = job("job-1", createdAt = 100L)
        assertTrue(repository.enqueue(job))
        repository.claimDue(100L)
        assertTrue(repository.fail("job-1", 200L, "secret-token-should-not-persist"))
        assertEquals("Extraction attempt failed.", repository.list().single().lastSafeError)
    }

    @Test fun responseValidationFailurePersistsAnAccurateSafeCategory() {
        val repository = repository()
        assertTrue(repository.enqueue(job("response-failure", createdAt = 100L)))
        repository.claimDue(100L)
        assertTrue(repository.fail("response-failure", 200L, "response"))

        assertEquals(
            "Provider response failed validation.",
            repository.list().single().lastSafeError,
        )
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
        val overflowJob =
            job(
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
        val earlier =
            job("earlier", createdAt = 100L).copy(
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

    @Test fun olderRetryBlocksNewerJobEvenWhenIdsSortInReverseChronologicalOrder() {
        val repository = MemoryExtractionJobRepository(CodecBackedJobStore())
        val older =
            job("z-older", createdAt = 100L).copy(
                attemptCount = 1,
                state = MemoryExtractionJob.State.RETRY_WAIT,
                nextAttemptAtEpochMillis = 2_000L,
            )
        val newer = job("a-newer", createdAt = 200L)

        assertTrue(repository.enqueue(older))
        assertTrue(repository.enqueue(newer))

        assertEquals(null, repository.claimDue(1_000L))
        assertEquals(
            MemoryExtractionJob.State.PENDING,
            repository.list().first { it.id == "a-newer" }.state,
        )
        assertEquals("z-older", repository.claimDue(2_000L)?.id)
    }

    @Test fun exhaustedDueJobDoesNotBlockAnotherDueJob() {
        val repository = repository()
        val exhausted =
            job(
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

    @Test fun retryPermanentFailuresReArmsOnlyTheRequestedConversation() {
        val repository = repository()
        val failed =
            job("failed", createdAt = 100L, attemptCount = 5).copy(
                state = MemoryExtractionJob.State.PERMANENT_FAILURE,
                lastSafeError = "Retry limit reached.",
            )
        val other =
            job("other", createdAt = 100L, attemptCount = 5).copy(
                conversationId = "conversation-2",
                state = MemoryExtractionJob.State.PERMANENT_FAILURE,
                lastSafeError = "Retry limit reached.",
            )
        assertTrue(repository.enqueue(failed))
        assertTrue(repository.enqueue(other))

        assertEquals(1, repository.retryPermanentFailures("conversation-1", 500L, "replacement-handle"))

        val retried = repository.list().first { it.id == "failed" }
        assertEquals(MemoryExtractionJob.State.RETRY_WAIT, retried.state)
        assertEquals(0, retried.attemptCount)
        assertEquals(500L, retried.nextAttemptAtEpochMillis)
        assertEquals(null, retried.lastSafeError)
        assertEquals("replacement-handle", retried.credentialHandle)
        assertEquals("opaque-handle", repository.list().first { it.id == "other" }.credentialHandle)
        assertEquals(
            MemoryExtractionJob.State.PERMANENT_FAILURE,
            repository.list().first { it.id == "other" }.state,
        )
    }

    @Test fun retryRejectsMissingCredentialWithoutChangingFailedJobs() {
        val repository = repository()
        val failed =
            job("failed", createdAt = 100L).copy(
                state = MemoryExtractionJob.State.PERMANENT_FAILURE,
            )
        repository.enqueue(failed)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            repository.retryPermanentFailures("conversation-1", 500L, " ")
        }
        assertEquals(listOf(failed), repository.list())
    }

    @Test fun separateRepositoryInstancesSerializeReadModifyWrite() {
        val firstRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val secondRead = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val store =
            object : MemoryExtractionJobPersistence {
                private var jobs = emptyList<MemoryExtractionJob>()
                private val reads = java.util.concurrent.atomic.AtomicInteger()

                override fun load(): MemoryExtractionJobLoadResult {
                    val snapshot = jobs.toList()
                    if (reads.incrementAndGet() == 1) {
                        firstRead.countDown()
                        check(releaseRead.await(5, TimeUnit.SECONDS))
                    } else {
                        secondRead.countDown()
                    }
                    return MemoryExtractionJobLoadResult.Loaded(snapshot)
                }

                override fun save(jobs: List<MemoryExtractionJob>) {
                    this.jobs = jobs.toList()
                }
            }
        val first = MemoryExtractionJobRepository(store)
        val second = MemoryExtractionJobRepository(store)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstEnqueue = executor.submit<Boolean> { first.enqueue(job("first", 100L)) }
            assertTrue(firstRead.await(5, TimeUnit.SECONDS))
            val secondEnqueue =
                executor.submit<Boolean> {
                    secondStarted.countDown()
                    second.enqueue(job("second", 200L))
                }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            org.junit.Assert.assertFalse(secondRead.await(200, TimeUnit.MILLISECONDS))
            releaseRead.countDown()
            assertTrue(firstEnqueue.get(5, TimeUnit.SECONDS))
            assertTrue(secondEnqueue.get(5, TimeUnit.SECONDS))
            assertEquals(setOf("first", "second"), first.list().map { it.id }.toSet())
        } finally {
            releaseRead.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun separateRepositoryInstancesCannotClaimTheSameJob() {
        val store = InMemoryJobStore()
        val first = MemoryExtractionJobRepository(store)
        val second = MemoryExtractionJobRepository(store)
        first.enqueue(job("only", 100L))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val claims =
                listOf(first, second).map { repository ->
                    executor.submit<MemoryExtractionJob?> {
                        check(start.await(5, TimeUnit.SECONDS))
                        repository.claimDue(100L)
                    }
                }
            start.countDown()
            assertEquals(1, claims.mapNotNull { it.get(5, TimeUnit.SECONDS) }.size)
            assertEquals(1, first.list().single().attemptCount)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun repository(): MemoryExtractionJobRepository = MemoryExtractionJobRepository(InMemoryJobStore())

    private class CodecBackedJobStore : MemoryExtractionJobPersistence {
        private var encoded: ByteArray? = null

        override fun save(jobs: List<MemoryExtractionJob>) {
            encoded = MemoryExtractionJobCodec.encode(jobs)
        }

        override fun load(): MemoryExtractionJobLoadResult =
            encoded?.let {
                MemoryExtractionJobLoadResult.Loaded(MemoryExtractionJobCodec.decode(it))
            } ?: MemoryExtractionJobLoadResult.Missing
    }

    private class InMemoryJobStore : MemoryExtractionJobPersistence {
        private var savedJobs: List<MemoryExtractionJob>? = null

        override fun save(jobs: List<MemoryExtractionJob>) {
            savedJobs = jobs.toList()
        }

        override fun load(): MemoryExtractionJobLoadResult =
            savedJobs?.let(MemoryExtractionJobLoadResult::Loaded)
                ?: MemoryExtractionJobLoadResult.Missing
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
