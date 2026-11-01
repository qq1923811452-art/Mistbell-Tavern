package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingRuntimeStatusPolicyTest {
    @Test
    fun reportsVerifiedNpuRuntimeForAFreshProcessWithoutClaimingExecution() {
        val reported = EmbeddingRuntimeStatusPolicy.report(
            selected = InferenceBackend.NPU,
            runtimeEvidenceVerified = true,
            activeInProcess = false,
        )
        assertEquals(InferenceBackend.UNKNOWN, reported.backend)
        assertEquals(BackendEvidence.RUNTIME_REPORTED, reported.evidence)
        // The restart case that used to read as "unverified" must stay constructible.
        val snapshot = status(InferencePhase.INITIALIZED, reported)
        assertEquals(BackendEvidence.RUNTIME_REPORTED, snapshot.backendEvidence)
    }

    @Test
    fun reportsNpuAsActiveOnlyAfterAnInferenceInThisProcess() {
        val reported = EmbeddingRuntimeStatusPolicy.report(
            selected = InferenceBackend.NPU,
            runtimeEvidenceVerified = true,
            activeInProcess = true,
        )
        assertEquals(InferenceBackend.NPU, reported.backend)
        assertEquals(BackendEvidence.RUNTIME_REPORTED, reported.evidence)
        assertEquals(InferenceBackend.NPU, status(InferencePhase.READY, reported).backend)
    }

    @Test
    fun keepsUnverifiedNpuUnlabelledAndConfiguredOnly() {
        val reported = EmbeddingRuntimeStatusPolicy.report(
            selected = InferenceBackend.NPU,
            runtimeEvidenceVerified = false,
            activeInProcess = true,
        )
        assertEquals(InferenceBackend.UNKNOWN, reported.backend)
        assertEquals(BackendEvidence.CONFIGURED_ONLY, reported.evidence)
        val snapshot = status(InferencePhase.INITIALIZED, reported)
        assertEquals(InferenceBackend.UNKNOWN, snapshot.backend)
        assertEquals(BackendEvidence.CONFIGURED_ONLY, snapshot.backendEvidence)
    }

    @Test
    fun keepsNonNpuBackendsAtConfiguredOnly() {
        for (backend in listOf(InferenceBackend.GPU, InferenceBackend.CPU)) {
            val reported = EmbeddingRuntimeStatusPolicy.report(
                selected = backend,
                runtimeEvidenceVerified = false,
                activeInProcess = true,
            )
            assertEquals(backend, reported.backend)
            assertEquals(BackendEvidence.CONFIGURED_ONLY, reported.evidence)
        }
    }

    private fun status(phase: InferencePhase, reported: EmbeddingRuntimeStatusPolicy.Reported) =
        InferenceRuntimeStatus(
            phase = phase,
            modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
            backend = reported.backend,
            runtimeId = "runtime",
            backendEvidence = reported.evidence,
            acceleratorName = "NPU",
        )
}
