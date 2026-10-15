package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QualcommNpuRuntimeAvailabilityTest {
    @Test
    fun `missing dispatch library blocks unsafe NPU initialization`() {
        val directory = Files.createTempDirectory("qnn-runtime-missing-").toFile()
        try {
            QualcommNpuRuntimeAvailability.requiredLibraries
                .filterNot { it == QualcommNpuRuntimeAvailability.DISPATCH_LIBRARY }
                .forEach { File(directory, it).writeBytes(byteArrayOf(1)) }

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
    fun `empty native library blocks unsafe NPU initialization`() {
        val directory = Files.createTempDirectory("qnn-runtime-empty-").toFile()
        try {
            QualcommNpuRuntimeAvailability.requiredLibraries.forEach {
                File(directory, it).writeBytes(byteArrayOf(1))
            }
            File(directory, "libQnnHtp.so").writeBytes(byteArrayOf())

            assertTrue(
                QualcommNpuRuntimeAvailability
                    .missingLibraries(directory)
                    .contains("libQnnHtp.so"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `complete nonempty runtime permits NPU initialization attempt`() {
        val directory = Files.createTempDirectory("qnn-runtime-ready-").toFile()
        try {
            QualcommNpuRuntimeAvailability.requiredLibraries.forEach {
                File(directory, it).writeBytes(byteArrayOf(1))
            }

            assertEquals(emptyList<String>(), QualcommNpuRuntimeAvailability.missingLibraries(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
