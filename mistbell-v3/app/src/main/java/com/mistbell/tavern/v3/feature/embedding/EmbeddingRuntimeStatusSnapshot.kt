package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus

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

    private const val RESTORED_RUNTIME_ID = "persisted-evidence"
}
