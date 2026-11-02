package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.diagnostics.bestEffortDiagnostic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryExtractionDiagnosticTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failedIdleDiagnosticStillCompletesWait() =
        runBlocking {
            val repository = MemoryExtractionJobRepository(InMemoryPersistence())
            val diagnostics = DiagnosticLogStore(temporaryFolder.newFile())
            val attempt = MemoryExtractionAttempt()

            MemoryExtractionQueueRunner(repository).runNext(
                buildInput = { Unit },
                execute = { _, _, _ -> error("No work expected") },
                observer = { result ->
                    if (result == MemoryExtractionQueueRunner.Result.Idle) {
                        bestEffortDiagnostic { diagnostics.info("memory.extraction.worker.idle") }
                        attempt.completion.complete(false)
                    }
                },
            )

            assertTrue(attempt.completion.isCompleted)
            assertFalse(attempt.await())
        }

    @Test
    fun failedOutcomeAndFinishedDiagnosticsPreserveQueueStateAndCompleteWait() =
        runBlocking {
            val cases =
                listOf(
                    MemoryExtractionQueueRunner.ExecutionResult.Success to MemoryExtractionJob.State.COMPLETED,
                    MemoryExtractionQueueRunner.ExecutionResult.Failure("network") to
                        MemoryExtractionJob.State.RETRY_WAIT,
                    MemoryExtractionQueueRunner.ExecutionResult.Failure("client", permanent = true) to
                        MemoryExtractionJob.State.PERMANENT_FAILURE,
                )
            val diagnostics = DiagnosticLogStore(temporaryFolder.newFile())
            for ((outcome, expectedState) in cases) {
                val repository = MemoryExtractionJobRepository(InMemoryPersistence())
                repository.enqueue(
                    MemoryExtractionJob(
                        id = "test-job",
                        conversationId = "conversation-1",
                        turnIds = listOf("turn-1"),
                        providerProfileId = "memory",
                        credentialHandle = "opaque-handle",
                        createdAtEpochMillis = 0L,
                    ),
                )
                val attempt = MemoryExtractionAttempt()
                MemoryExtractionQueueRunner(repository).runNext(
                    buildInput = { Unit },
                    execute = { _, _, complete ->
                        attempt.bindCompletion(complete)
                        attempt.commit {
                            bestEffortDiagnostic {
                                if (outcome == MemoryExtractionQueueRunner.ExecutionResult.Success) {
                                    diagnostics.info("memory.extraction.job.saved")
                                } else {
                                    diagnostics.warn("memory.extraction.job.failed")
                                }
                            }
                            complete(outcome)
                        }
                    },
                    observer = { result ->
                        if (result is MemoryExtractionQueueRunner.Result.Finished) {
                            bestEffortDiagnostic { diagnostics.info("memory.extraction.job.finished") }
                            attempt.completion.complete(true)
                        }
                    },
                )

                assertTrue(attempt.completion.isCompleted)
                assertTrue(attempt.await())
                assertEquals(expectedState, repository.list().single().state)
            }
        }

    private class InMemoryPersistence : MemoryExtractionJobPersistence {
        private var jobs = emptyList<MemoryExtractionJob>()

        override fun load() = MemoryExtractionJobLoadResult.Loaded(jobs)

        override fun save(jobs: List<MemoryExtractionJob>) {
            this.jobs = jobs.toList()
        }
    }
}
