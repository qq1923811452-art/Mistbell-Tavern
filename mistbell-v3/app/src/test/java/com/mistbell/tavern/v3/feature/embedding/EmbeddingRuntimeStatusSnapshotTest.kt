package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddingRuntimeStatusSnapshotTest {
    @Test
    fun verifiedEvidenceSurvivesARestartWithoutClaimingNpuExecution() {
        val status = EmbeddingRuntimeStatusSnapshot.restored("google/embeddinggemma-2", "NPU", evidenceVerified = true)
        assertEquals(BackendEvidence.RUNTIME_REPORTED, status.backendEvidence)
        assertEquals(InferenceBackend.UNKNOWN, status.backend)
        assertEquals(InferencePhase.NOT_INITIALIZED, status.phase)
        assertEquals("NPU", status.acceleratorName)
    }

    @Test
    fun missingEvidenceStaysUnverified() {
        val status = EmbeddingRuntimeStatusSnapshot.restored("google/embeddinggemma-2", null, evidenceVerified = false)
        assertEquals(BackendEvidence.NOT_AVAILABLE, status.backendEvidence)
        assertEquals(InferenceBackend.UNKNOWN, status.backend)
        assertNull(status.acceleratorName)
    }
}
