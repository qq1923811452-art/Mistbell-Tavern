package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Restores the *verification* verdict from persisted evidence after a restart.
 *
 * A cold process has no engine and therefore no execution to report. Showing "未验证" in that
 * state is wrong once the runtime manifest was already verified on this device, so the evidence
 * travels separately from the backend label: the backend stays UNKNOWN until this process really
 * runs an inference, while the evidence level reflects the validated manifest.
 */
internal object EmbeddingRuntimeStatusSnapshot {
    fun restored(
        modelId: String,
        acceleratorName: String?,
        evidenceVerified: Boolean,
    ): InferenceRuntimeStatus {
        val evidence = if (evidenceVerified) BackendEvidence.RUNTIME_REPORTED else BackendEvidence.NOT_AVAILABLE
        return InferenceRuntimeStatus(
            phase = InferencePhase.NOT_INITIALIZED,
            modelId = modelId,
            backend = InferenceBackend.UNKNOWN,
            runtimeId = RESTORED_RUNTIME_ID,
            backendEvidence = evidence,
            acceleratorName = acceleratorName,
        )
    }

    /** Live failures win; a closed/idle engine must not hide durable verification. */
    fun resolve(
        live: InferenceRuntimeStatus?,
        previous: InferenceRuntimeStatus,
        restored: InferenceRuntimeStatus,
    ): InferenceRuntimeStatus =
        when {
            live != null && live.phase != InferencePhase.NOT_INITIALIZED -> live
            previous.phase == InferencePhase.FAILED -> previous
            else -> restored
        }

    /** Runs in the caller's scope: leaving settings or changing a key cancels publication. */
    suspend fun refresh(
        load: suspend () -> InferenceRuntimeStatus,
        isCurrent: () -> Boolean,
        publish: (InferenceRuntimeStatus) -> Unit,
    ) {
        val status =
            try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Fail closed if a model disappeared or could not be validated.
                InferenceRuntimeStatus.idle()
            }
        coroutineContext.ensureActive()
        if (isCurrent()) publish(status)
    }

    private const val RESTORED_RUNTIME_ID = "persisted-evidence"
}
