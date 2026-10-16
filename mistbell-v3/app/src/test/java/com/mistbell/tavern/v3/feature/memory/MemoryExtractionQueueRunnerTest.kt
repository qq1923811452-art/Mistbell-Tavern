package com.mistbell.tavern.v3.feature.memory

import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryExtractionQueueRunnerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun successfulExecutionCompletesClaimedJob() {
        val repository = repository()
        repository.enqueue(job("success"))
        val runner = MemoryExtractionQueueRunner(repository) { 100L }

        val dispatch = runner.runNext(
            buildInput = { it.turnIds },
            execute = { _, _, complete -> complete(MemoryExtractionQueueRunner.ExecutionResult.Success) },
        )

        assertTrue(dispatch is MemoryExtractionQueueRunner.Result.Started)
        assertEquals(MemoryExtractionJob.State.COMPLETED, repository.list().single().state)
    }

    @Test fun executionFailureSchedulesRetryWithoutPersistingRawError() {
        val repository = repository()
        repository.enqueue(job("failure"))
        val runner = MemoryExtractionQueueRunner(repository) { 10_000L }

        runner.runNext(
            buildInput = { it.turnIds },
            execute = { _, _, complete ->
                complete(MemoryExtractionQueueRunner.ExecutionResult.Failure("network"))
            },
        )

        val failed = repository.list().single()
        assertEquals(MemoryExtractionJob.State.RETRY_WAIT, failed.state)
        assertEquals("Network request failed.", failed.lastSafeError)
        assertEquals(40_000L, failed.nextAttemptAtEpochMillis)
    }

    @Test fun sourceReconstructionFailureIsPermanentAndDoesNotDispatchApi() {
        val repository = repository()
        repository.enqueue(job("missing-source"))
        val runner = MemoryExtractionQueueRunner(repository) { 10_000L }
        var dispatched = false

        runner.runNext(
            buildInput = { throw IllegalStateException("private message or credential") },
            execute = { _, _, _ -> dispatched = true },
        )

        assertTrue(!dispatched)
        val failed = repository.list().single()
        assertEquals(MemoryExtractionJob.State.PERMANENT_FAILURE, failed.state)
        assertEquals("Extraction attempt failed.", failed.lastSafeError)
    }

    @Test fun duplicateCompletionCallbackCannotCompleteOrFailTwice() {
        val repository = repository()
        repository.enqueue(job("double-callback"))
        val runner = MemoryExtractionQueueRunner(repository) { 10_000L }

        runner.runNext(
            buildInput = { it.id },
            execute = { _, _, complete ->
                complete(MemoryExtractionQueueRunner.ExecutionResult.Success)
                complete(MemoryExtractionQueueRunner.ExecutionResult.Failure("network"))
            },
        )

        assertEquals(MemoryExtractionJob.State.COMPLETED, repository.list().single().state)
    }

    @Test fun noDueJobReturnsIdle() {
        val repository = repository()
        repository.enqueue(job("future").copy(nextAttemptAtEpochMillis = 50_000L))
        val result = MemoryExtractionQueueRunner(repository) { 10_000L }.runNext(
            buildInput = { it.id },
            execute = { _, _, _ -> error("Must not dispatch a future job.") },
        )
        assertEquals(MemoryExtractionQueueRunner.Result.Idle, result)
    }

    private fun repository(): MemoryExtractionJobRepository {
        val root: File = folder.newFolder()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = root
        }
        return MemoryExtractionJobRepository(MemoryExtractionJobStore(context))
    }

    private fun job(id: String) = MemoryExtractionJob(
        id = id,
        conversationId = "conversation-1",
        turnIds = listOf("turn-1"),
        providerProfileId = "memory",
        credentialHandle = "saved-opaque-handle",
        createdAtEpochMillis = 1_000L,
    )
}
