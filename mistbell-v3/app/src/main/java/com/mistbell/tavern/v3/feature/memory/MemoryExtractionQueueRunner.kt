package com.mistbell.tavern.v3.feature.memory

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes one persisted extraction job at a time.
 *
 * The queue owns retry state; callers provide source reconstruction and the actual pipeline so
 * this coordinator remains deterministic and testable. A callback is guarded against accidental
 * double delivery, and synchronous setup failures are recorded just like asynchronous failures.
 */
class MemoryExtractionQueueRunner(
    private val repository: MemoryExtractionJobRepository,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data object Idle : Result
        data class Finished(
            val jobId: String,
            val state: MemoryExtractionJob.State,
        ) : Result
        data class Started(val jobId: String) : Result

        /** Queue-state persistence failed; the caller must stop this worker attempt and retry later. */
        data class PersistenceFailure(val jobId: String) : Result
    }

    sealed interface ExecutionResult {
        data object Success : ExecutionResult
        data class Failure(
            val safeCategory: String = "unknown",
            val permanent: Boolean = false,
        ) : ExecutionResult
    }

    fun recoverInterrupted(): Int = repository.recoverInterrupted(now())

    /**
     * Claims the oldest due job and dispatches it. The source builder must reject missing or
     * incomplete history rather than silently sending a partial batch.
     */
    fun <T> runNext(
        buildInput: (MemoryExtractionJob) -> T,
        execute: (MemoryExtractionJob, T, (ExecutionResult) -> Unit) -> Unit,
        observer: (Result) -> Unit = {},
    ): Result {
        val safeObserver: (Result) -> Unit = { result -> runCatching { observer(result) } }
        val job = repository.claimDue(now()) ?: return Result.Idle.also(safeObserver)
        val finished = AtomicBoolean(false)
        // Inline callbacks can finish before runNext returns; asynchronous callbacks still return Started.
        val synchronousResult = AtomicReference<Result?>(null)

        fun finish(result: ExecutionResult) {
            if (!finished.compareAndSet(false, true)) return
            val currentTime = runCatching(::now).getOrElse { job.createdAtEpochMillis }
            val terminal: Result = try {
                when (result) {
                    ExecutionResult.Success -> repository.complete(job.id)
                    is ExecutionResult.Failure -> repository.fail(
                        id = job.id,
                        now = currentTime,
                        safeError = result.safeCategory,
                        permanent = result.permanent,
                    )
                }
                val state = repository.list().firstOrNull { it.id == job.id }?.state
                    ?: MemoryExtractionJob.State.PERMANENT_FAILURE
                Result.Finished(job.id, state)
            } catch (_: Exception) {
                // Do not claim success when the durable state transition failed. The job may
                // remain RUNNING; the next worker invocation recovers it to RETRY_WAIT.
                Result.PersistenceFailure(job.id)
            }
            synchronousResult.set(terminal)
            safeObserver(terminal)
        }

        val input = try {
            buildInput(job)
        } catch (_: Exception) {
            finish(ExecutionResult.Failure(permanent = true))
            return synchronousResult.get() ?: Result.Started(job.id)
        }

        try {
            execute(job, input, ::finish)
        } catch (_: Exception) {
            finish(ExecutionResult.Failure())
        }
        return synchronousResult.get() ?: Result.Started(job.id)
    }

    private fun now(): Long = clockMillis().also { require(it >= 0L) }
}
