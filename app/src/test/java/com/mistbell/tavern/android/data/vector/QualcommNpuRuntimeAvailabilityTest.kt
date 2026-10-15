package com.mistbell.tavern.android.data.vector

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommNpuRuntimeAvailabilityTest {
    @Test
    fun `missing dispatch library blocks unsafe NPU initialization`() {
        val directory = createTempDir(prefix = "qnn-runtime-missing-")
        try {
            QualcommNpuRuntimeAvailability.requiredLibraries
                .filterNot { it == QualcommNpuRuntimeAvailability.DISPATCH_LIBRARY }
                .forEach { File(directory, it).createNewFile() }

            assertTrue(
                QualcommNpuRuntimeAvailability
                    .missingLibraries(directory)
                    .contains(QualcommNpuRuntimeAvailability.DISPATCH_LIBRARY),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `complete matching runtime permits NPU initialization attempt`() {
        val directory = createTempDir(prefix = "qnn-runtime-ready-")
        try {
            QualcommNpuRuntimeAvailability.requiredLibraries.forEach {
                File(directory, it).createNewFile()
            }

            assertEquals(emptyList<String>(), QualcommNpuRuntimeAvailability.missingLibraries(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
