package com.mistbell.tavern.v3.feature.memory

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionAttemptTest {
    @Test
    fun completedAndIdleDispatchesReturnWithoutCancellingRequest() = runBlocking {
        for (hasJob in listOf(true, false)) {
            val attempt = MemoryExtractionAttempt()
            val call = newCall()
            attempt.attachCall(call)
            attempt.completion.complete(hasJob)
            assertEquals(hasJob, attempt.await(1_000))
            assertFalse(call.isCanceled())
        }
    }

    @Test
    fun missingCallbackTimesOutMarksRetryableFailureAndCancelsCall() = runBlocking {
        val attempt = MemoryExtractionAttempt()
        val call = newCall()
        val failures = mutableListOf<MemoryExtractionQueueRunner.ExecutionResult>()
        attempt.attachCall(call)
        attempt.bindCompletion { failures.add(it) }
        var timedOut = false
        try {
            attempt.await(1)
        } catch (_: IOException) {
            timedOut = true
        }
        assertTrue(timedOut)
        assertTrue(call.isCanceled())
        assertEquals(listOf(MemoryExtractionQueueRunner.ExecutionResult.Failure("timeout")), failures)
    }

    @Test
    fun workerCancellationPropagatesAndCancelsOutstandingCall() = runBlocking {
        val attempt = MemoryExtractionAttempt()
        val call = newCall()
        val failures = mutableListOf<MemoryExtractionQueueRunner.ExecutionResult>()
        attempt.attachCall(call)
        attempt.bindCompletion { failures.add(it) }
        val started = CompletableDeferred<Unit>()
        val waiting = async {
            started.complete(Unit)
            attempt.await(10_000)
        }
        started.await()
        waiting.cancel(CancellationException("Worker stopped"))
        waiting.join()
        assertTrue(waiting.isCancelled)
        assertTrue(call.isCanceled())
        assertEquals(listOf(MemoryExtractionQueueRunner.ExecutionResult.Failure("cancelled")), failures)
    }

    @Test
    fun timeoutPersistsRetryAndLateCallbackCannotCompleteJob() = runBlocking {
        val store = object : MemoryExtractionJobPersistence {
            var jobs: List<MemoryExtractionJob> = emptyList()
            override fun save(jobs: List<MemoryExtractionJob>) { this.jobs = jobs.toList() }
            override fun load() = MemoryExtractionJobLoadResult.Loaded(jobs)
        }
        val repository = MemoryExtractionJobRepository(store)
        repository.enqueue(MemoryExtractionJob(
            id = "timeout-job",
            conversationId = "conversation-1",
            turnIds = listOf("turn-1"),
            providerProfileId = "memory",
            credentialHandle = "opaque-handle",
            createdAtEpochMillis = 1_000L,
        ))
        val attempt = MemoryExtractionAttempt()
        var lateComplete: ((MemoryExtractionQueueRunner.ExecutionResult) -> Unit)? = null
        MemoryExtractionQueueRunner(repository) { 10_000L }.runNext(
            buildInput = { Unit },
            execute = { _, _, complete ->
                lateComplete = complete
                attempt.bindCompletion(complete)
                attempt.attachCall(newCall())
            },
            observer = { result ->
                if (result is MemoryExtractionQueueRunner.Result.Finished) attempt.completion.complete(true)
            },
        )
        try {
            attempt.await(1)
        } catch (_: IOException) {
            // A missing network callback must leave the durable queue retryable.
        }
        assertEquals(MemoryExtractionJob.State.RETRY_WAIT, repository.list().single().state)
        requireNotNull(lateComplete)(MemoryExtractionQueueRunner.ExecutionResult.Success)
        assertEquals(MemoryExtractionJob.State.RETRY_WAIT, repository.list().single().state)
    }

    @Test
    fun expiredAttemptCannotEnterPersistence() = runBlocking {
        val attempt = MemoryExtractionAttempt()
        attempt.bindCompletion { }
        try {
            attempt.await(1)
        } catch (_: IOException) {
            // Expiry closes the persistence gate as well as the queue callback.
        }
        var writes = 0
        attempt.commit { writes += 1 }
        assertEquals(0, writes)
    }

    @Test
    fun committedAttemptCannotBeFailedOrCommittedAgain() = runBlocking {
        val attempt = MemoryExtractionAttempt()
        var failures = 0
        var writes = 0
        attempt.bindCompletion { failures += 1 }
        attempt.commit {
            writes += 1
            attempt.completion.complete(true)
        }
        attempt.commit { writes += 1 }
        assertTrue(attempt.await(1_000))
        assertEquals(1, writes)
        assertEquals(0, failures)
    }

    private fun newCall() = OkHttpClient().newCall(Request.Builder().url("http://localhost/").build())
}
