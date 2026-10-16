package com.mistbell.tavern.v3.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceBackendSelectionTest {
    @Test
    fun selectsFirstAvailableRuntimeInPreferenceOrder() {
        val probes = listOf(
            RuntimeProbe("litert-npu", false, InferenceBackend.NPU),
            RuntimeProbe("litert-gpu", true, InferenceBackend.GPU),
            RuntimeProbe("cpu-reference", true, InferenceBackend.CPU),
        )
        val preference = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU)
        val selected = InferenceBackendSelection.select(preference, probes)
        assertEquals("litert-gpu", selected?.runtimeId)
        assertEquals(
            "Preferred NPU backend was unavailable; using GPU via litert-gpu.",
            InferenceBackendSelection.fallbackReason(preference, selected),
        )
    }

    @Test
    fun unknownBackendIsNotAcceleratorEvidence() {
        val preference = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU)
        val selected = InferenceBackendSelection.select(
            preference,
            listOf(RuntimeProbe("unverified", true, InferenceBackend.UNKNOWN)),
        )
        assertNull(selected)
        assertEquals(
            "No available runtime reported a verified CPU, GPU, or NPU backend.",
            InferenceBackendSelection.fallbackReason(preference, selected),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRuntimeIdsAreRejected() {
        InferenceBackendSelection.select(
            listOf(InferenceBackend.CPU),
            listOf(RuntimeProbe("same", true, InferenceBackend.CPU), RuntimeProbe("same", true, InferenceBackend.CPU)),
        )
    }
}
