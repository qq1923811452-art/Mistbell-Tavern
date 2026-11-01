package com.mistbell.tavern.v3.feature.chat

import java.net.URI
/** Short, safe request labels for diagnostics: never keys, prompts or raw user input. */
internal object ChatRequestDiagnostics {
    fun modelLabel(modelId: String): String =
        modelId.trim().take(MAX_LABEL_CHARS).filter(::safeChar).ifEmpty { "unknown" }

    fun endpointHost(baseUrl: String): String =
        runCatching { URI(baseUrl.trim()).host }
            .getOrNull()
            ?.take(MAX_LABEL_CHARS)
            ?.filter(::safeChar)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun safeChar(char: Char): Boolean =
        char.isLetterOrDigit() || char in "._-:"

    private const val MAX_LABEL_CHARS = 64
}
