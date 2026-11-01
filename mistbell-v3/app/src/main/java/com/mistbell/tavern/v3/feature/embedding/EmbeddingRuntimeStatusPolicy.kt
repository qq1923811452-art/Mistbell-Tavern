package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend

/**
 * A configured delegate is not execution evidence, but a validated runtime manifest is stronger
 * than "configured only". These two facts are reported independently:
 *
 * - [Reported.backend] names NPU only while the engine is active in this process with validated
 *   evidence, so a status snapshot never claims hardware execution that has not happened here.
 * - [Reported.evidence] keeps the persisted manifest verdict, so restarting the app no longer
 *   downgrades a verified Qualcomm runtime to "unverified" just because no inference has run yet.
 *
 * Keeping this decision in one pure function prevents the snapshot path and the inference path
 * from drifting apart, which is exactly how a verified runtime previously looked unverified.
 */
internal object EmbeddingRuntimeStatusPolicy {
    data class Reported(val backend: InferenceBackend, val evidence: BackendEvidence)

    fun report(
        selected: InferenceBackend,
        runtimeEvidenceVerified: Boolean,
        activeInProcess: Boolean,
    ): Reported {
        val evidence = when {
            selected == InferenceBackend.NPU && runtimeEvidenceVerified -> BackendEvidence.RUNTIME_REPORTED
            else -> BackendEvidence.CONFIGURED_ONLY
        }
        val backend = when {
            selected == InferenceBackend.NPU && !(runtimeEvidenceVerified && activeInProcess) ->
                InferenceBackend.UNKNOWN
            else -> selected
        }
        return Reported(backend, evidence)
    }
}
