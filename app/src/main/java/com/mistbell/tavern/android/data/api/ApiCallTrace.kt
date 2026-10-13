package com.mistbell.tavern.android.data.api

import android.util.Log
import java.util.UUID

/**
 * Safe API-call diagnostics. Never logs authorization headers, API keys, or request/response bodies.
 * Filter Logcat with: adb logcat -s ApiCallTrace
 */
object ApiCallTrace {
    private const val TAG = "ApiCallTrace"

    fun newId(): String = UUID.randomUUID().toString().take(8)

    /** Finds the first app frame outside this tracer and the transport client. */
    fun caller(skipClassNames: Set<String> = emptySet()): String {
        val frame = Throwable().stackTrace.firstOrNull {
            val name = it.className
            !name.startsWith("java.") &&
                !name.startsWith("kotlin.") &&
                !name.startsWith("kotlinx.coroutines.") &&
                !name.startsWith("okhttp3.") &&
                !name.startsWith("com.mistbell.tavern.android.data.api.ApiCallTrace") &&
                !name.startsWith("com.mistbell.tavern.android.data.api.LlmClient") &&
                skipClassNames.none { skipped -> name.startsWith(skipped) }
        }
        return frame?.let { "${it.className}#${it.methodName}:${it.lineNumber}" } ?: "unknown"
    }

    fun log(
        id: String,
        kind: String,
        phase: String,
        detail: String,
    ) {
        Log.i(TAG, "id=$id kind=$kind phase=$phase $detail")
    }

    fun safeEndpoint(url: String): String =
        runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host ?: "unknown"}${uri.path.orEmpty()}"
        }.getOrDefault("<invalid-url>")
}
