package com.mistbell.tavern.v3.feature.chat

import java.util.Locale

/** Absence of a provider finish reason is not evidence of a natural stop. */
internal fun classifyCompletionFinishReason(reason: String?): ChatFinishReason =
    when (reason?.lowercase(Locale.ROOT)) {
        "stop", "end_turn" -> ChatFinishReason.STOP
        "length", "max_tokens" -> ChatFinishReason.LENGTH
        else -> ChatFinishReason.OTHER
    }

internal fun completionFinishReason(root: org.json.JSONObject): String? =
    root.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: root.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
            ?.takeIf { it.isNotBlank() && it != "null" }

internal fun completionDiagnostic(
    reason: String?,
    completionChars: Long,
): String {
    val safeReason =
        reason?.take(MAX_FINISH_REASON_LABEL_CHARS)
            ?.filter { it.isLetterOrDigit() || it == '_' }?.takeIf(String::isNotEmpty)
    return "providerFinishReason=${safeReason ?: "missing"}; completionChars=$completionChars"
}

private const val MAX_FINISH_REASON_LABEL_CHARS = 64
