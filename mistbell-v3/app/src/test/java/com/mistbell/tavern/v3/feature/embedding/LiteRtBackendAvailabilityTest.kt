package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.InferenceBackend
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtBackendAvailabilityTest {
    @Test
    fun cpuIsAlwaysAvailableButMissingPluginsDisableGpuAndNpu(): Unit {
        val directory = kotlin.io.path.createTempDirectory(prefix = "litert-native-libs").toFile()
        try {
            val result = LiteRtBackendAvailability.detect(
                nativeLibraryDir = directory,
                isArm64 = true,
                npuModelCompatible = true,
                npuRuntimeValidated = false,
            )

            assertTrue(result.isAvailable(InferenceBackend.CPU))
            assertFalse(result.isAvailable(InferenceBackend.GPU))
            assertFalse(result.isAvailable(InferenceBackend.NPU))
            assertTrue(result.gpuReason.orEmpty().contains("missing or empty"))
            assertTrue(result.npuReason.orEmpty().contains("validated"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun gpuPluginsPackagedInsideApkCountWhenNotExtractedToNativeLibraryDir(): Unit {
        val directory = kotlin.io.path.createTempDirectory(prefix = "litert-native-libs").toFile()
        val apk = kotlin.io.path.createTempFile(prefix = "litert-test", suffix = ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                LiteRtBackendAvailability.GPU_REQUIRED_LIBRARIES.forEach { name ->
                    zip.putNextEntry(ZipEntry("lib/arm64-v8a/$name"))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }

            val result = LiteRtBackendAvailability.detect(
                nativeLibraryDir = directory,
                isArm64 = true,
                npuModelCompatible = false,
                npuRuntimeValidated = false,
                apkFiles = listOf(apk),
            )

            assertTrue(result.isAvailable(InferenceBackend.GPU))
            assertFalse(result.isAvailable(InferenceBackend.NPU))
            assertTrue(result.npuReason.orEmpty().contains("missing or empty"))
        } finally {
            directory.deleteRecursively()
            apk.delete()
        }
    }

    @Test
    fun npuRequiresTargetModelRuntimeManifestAndAllRequiredLibraries(): Unit {
        val directory = kotlin.io.path.createTempDirectory(prefix = "litert-native-libs").toFile()
        try {
            LiteRtBackendAvailability.SM8650_NPU_REQUIRED_LIBRARIES.forEach { File(directory, it).writeBytes(byteArrayOf(1)) }
            val missingManifest = LiteRtBackendAvailability.detect(
                nativeLibraryDir = directory,
                isArm64 = true,
                npuModelCompatible = true,
                npuRuntimeValidated = false,
            )
            assertFalse(missingManifest.isAvailable(InferenceBackend.NPU))

            val verifiedPackage = LiteRtBackendAvailability.detect(
                nativeLibraryDir = directory,
                isArm64 = true,
                npuModelCompatible = true,
                npuRuntimeValidated = true,
            )
            assertTrue(verifiedPackage.isAvailable(InferenceBackend.NPU))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun npuRejectsPortableOrImportedModelEvenWhenLibrariesArePresent(): Unit {
        val directory = kotlin.io.path.createTempDirectory(prefix = "litert-native-libs").toFile()
        try {
            LiteRtBackendAvailability.SM8650_NPU_REQUIRED_LIBRARIES.forEach { File(directory, it).writeBytes(byteArrayOf(1)) }
            val result = LiteRtBackendAvailability.detect(
                nativeLibraryDir = directory,
                isArm64 = true,
                npuModelCompatible = false,
                npuRuntimeValidated = true,
            )
            assertFalse(result.isAvailable(InferenceBackend.NPU))
            assertTrue(result.npuReason.orEmpty().contains("SHA-256"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun npuIsRejectedBelowAndroidApi31WhileCpuAndGpuRemainIndependent(): Unit {
        val directory = kotlin.io.path.createTempDirectory(prefix = "litert-native-libs").toFile()
        try {
            LiteRtBackendAvailability.SM8650_NPU_REQUIRED_LIBRARIES.forEach {
                File(directory, it).writeBytes(byteArrayOf(1))
            }
            LiteRtBackendAvailability.GPU_REQUIRED_LIBRARIES.forEach {
                File(directory, it).writeBytes(byteArrayOf(1))
            }

            val result = LiteRtBackendAvailability.detect(
                nativeLibraryDir = directory,
                isArm64 = true,
                npuModelCompatible = true,
                npuRuntimeValidated = true,
                androidApiLevel = 30,
            )

            assertTrue(result.isAvailable(InferenceBackend.CPU))
            assertTrue(result.isAvailable(InferenceBackend.GPU))
            assertFalse(result.isAvailable(InferenceBackend.NPU))
            assertTrue(result.npuReason.orEmpty().contains("Android API 31"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
