package com.mistbell.tavern.v3.feature.memory

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call

/** Owns the request and bounded wait for one queue dispatch, not the worker's lifetime. */
internal class MemoryExtractionAttempt {
    val completion = CompletableDeferred<Boolean>()
    private val commitLock = Any()
    private var closed = false
    private var call: Call? = null
    private var finishExecution: ((MemoryExtractionQueueRunner.ExecutionResult) -> Unit)? = null

    /** Runs persistence and its terminal queue callback under the same gate as expiry. */
    fun commit(block: () -> Unit) {
        synchronized(commitLock) {
            if (!closed) {
                closed = true
                block()
            }
        }
    }

    fun bindCompletion(complete: (MemoryExtractionQueueRunner.ExecutionResult) -> Unit) {
        finishExecution = complete
    }

    fun attachCall(request: Call) {
        call = request
    }

    suspend fun await(timeoutMillis: Long = MemoryExtractionTimeouts.ATTEMPT_MILLIS): Boolean {
        return try {
            val hasJob = withTimeoutOrNull(timeoutMillis) { completion.await() }
            if (hasJob == null) {
                abort("timeout")
                throw IOException("Memory-extraction completion timed out.")
            }
            hasJob
        } catch (cancelled: CancellationException) {
            abort("cancelled")
            throw cancelled
        }
    }

    private fun abort(category: String) {
        try {
            // Persist the retryable failure before cancellation can race a network callback.
            // The runner's single-delivery guard rejects any later callback.
            commit {
                finishExecution?.invoke(MemoryExtractionQueueRunner.ExecutionResult.Failure(category))
            }
        } finally {
            call?.cancel()
            completion.cancel()
        }
    }
}
