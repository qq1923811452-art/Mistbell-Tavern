package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.InferenceBackend
import java.io.File
import java.util.zip.ZipFile

/**
 * Prevents asking LiteRT-LM to initialize an accelerator whose platform or native runtime
 * prerequisites are not met. This is a packaging/platform preflight only; it does not prove
 * that hardware actually executes an operation.
 */
data class LiteRtBackendAvailability private constructor(
    private val availableBackends: Set<InferenceBackend>,
    val npuReason: String?,
    val gpuReason: String?,
) {
    fun isAvailable(backend: InferenceBackend): Boolean = backend in availableBackends

    companion object {
        const val NPU_MIN_ANDROID_API = 31

        /**
         * SM8650 NPU requires the Qualcomm dispatch plugin plus matching QAIRT/QNN runtime files.
         * These names are a conservative minimum for the SM8650 / Hexagon V75 target.
         */
        val SM8650_NPU_REQUIRED_LIBRARIES = listOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnSystem.so",
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV75Skel.so",
            "libQnnHtpV75CalculatorStub.so",
        )

        /** The current AAR's JNI alone does not package these accelerator plugins. */
        val GPU_REQUIRED_LIBRARIES = listOf(
            "libLiteRtGpuAccelerator.so",
            "libLiteRtOpenClAccelerator.so",
        )

        fun detect(
            nativeLibraryDir: File,
            isArm64: Boolean,
            npuModelCompatible: Boolean,
            npuRuntimeValidated: Boolean = false,
            apkFiles: List<File> = emptyList(),
            androidApiLevel: Int = Int.MAX_VALUE,
            allowDiagnosticNpuProbe: Boolean = false,
        ): LiteRtBackendAvailability {
            // Android may load uncompressed native libraries directly from an APK instead of
            // extracting them into nativeLibraryDir. Check both installed representations so
            // preflight does not report packaged plugins as missing on such devices.
            fun isPackaged(name: String): Boolean =
                nativeLibraryDir.hasNonEmptyLibrary(name) || apkFiles.any { it.hasNonEmptyApkLibrary(name) }

            val missingNpu = SM8650_NPU_REQUIRED_LIBRARIES.filterNot(::isPackaged)
            val missingGpu = GPU_REQUIRED_LIBRARIES.filterNot(::isPackaged)
            val npuReasons = buildList {
                if (androidApiLevel < NPU_MIN_ANDROID_API) {
                    add("Android API $NPU_MIN_ANDROID_API or newer is required for LiteRT NPU; device API is $androidApiLevel")
                }
                if (!npuModelCompatible) add("installed model SHA-256 does not match the pinned Qualcomm SM8650 model")
                if (!isArm64) add("device ABI does not include arm64-v8a")
                if (!npuRuntimeValidated && !allowDiagnosticNpuProbe) {
                    add("no validated LiteRT-LM 0.18.0 / QAIRT runtime manifest is packaged")
                }
                if (missingNpu.isNotEmpty()) {
                    add("missing or empty Qualcomm runtime libraries: ${missingNpu.joinToString(",")}")
                }
            }
            val npuReason = npuReasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
            val gpuReasons = buildList {
                if (!isArm64) add("device ABI does not include arm64-v8a")
                if (missingGpu.isNotEmpty()) {
                    add("missing or empty LiteRT GPU accelerator libraries: ${missingGpu.joinToString(",")}")
                }
            }
            val gpuReason = gpuReasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
            val available = buildSet {
                add(InferenceBackend.CPU)
                if (npuReason == null) add(InferenceBackend.NPU)
                if (gpuReason == null) add(InferenceBackend.GPU)
            }
            return LiteRtBackendAvailability(available, npuReason, gpuReason)
        }

        private fun File.hasNonEmptyLibrary(name: String): Boolean {
            val library = File(this, name)
            return library.isFile && library.length() > 0L
        }

        private fun File.hasNonEmptyApkLibrary(name: String): Boolean {
            if (!isFile || length() <= 0L) return false
            return runCatching {
                ZipFile(this).use { apk ->
                    val entry = apk.getEntry("lib/arm64-v8a/" + name)
                    entry != null && !entry.isDirectory && entry.size > 0L
                }
            }.getOrDefault(false)
        }
    }
}
