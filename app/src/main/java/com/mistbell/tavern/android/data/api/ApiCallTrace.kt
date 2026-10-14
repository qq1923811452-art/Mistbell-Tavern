package com.mistbell.tavern.android.data.api

import android.util.Log
import java.util.UUID

/**
 * Safe API-call diagnostics. Never logs authorization headers, API keys, or request/response bodies.
 * Filter Logcat with: adb logcat -s ApiCallTrace
 */
object ApiCallTrace {
    private const val TAG = "ApiCallTrace"
    private const val TRACE_ID_LENGTH = 8

    fun newId(): String = UUID.randomUUID().toString().take(TRACE_ID_LENGTH)

    /** Finds the first app frame outside this tracer and the transport client. */
    fun caller(skipClassNames: Set<String> = emptySet()): String {
        val frame =
            Throwable("API call source trace").stackTrace.firstOrNull {
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
            val scheme =
                uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
                    ?: return@runCatching "<invalid-url>"
            val host = uri.host ?: return@runCatching "<invalid-url>"
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            val path = uri.path.orEmpty().trimEnd('/')
            val safePath =
                when {
                    path.endsWith("/chat/completions") -> "/.../chat/completions"
                    path.endsWith("/embeddings") -> "/.../embeddings"
                    path.endsWith("/messages") -> "/.../messages"
                    path.endsWith("/models") -> "/.../models"
                    path.isBlank() -> ""
                    else -> "/<redacted-path>"
                }
            "$scheme://$host$port$safePath"
        }.getOrDefault("<invalid-url>")
}
