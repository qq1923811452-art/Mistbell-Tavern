package com.mistbell.tavern.android.data.vector

import java.io.File

/**
 * Prevents LiteRT-LM from entering the Qualcomm dispatch path unless its matching dispatch
 * library and the SM8650 QAIRT runtime files are packaged beside the app's native libraries.
 */
internal object QualcommNpuRuntimeAvailability {
    const val DISPATCH_LIBRARY = "libLiteRtDispatch_Qualcomm.so"

    val requiredLibraries =
        listOf(
            DISPATCH_LIBRARY,
            "libQnnHtp.so",
            "libQnnSystem.so",
            "libQnnHtpPrepare.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV75Skel.so",
        )

    fun missingLibraries(nativeLibraryDir: File): List<String> =
        requiredLibraries.filterNot { libraryName ->
            val library = File(nativeLibraryDir, libraryName)
            library.isFile && library.length() > 0L
        }
}
