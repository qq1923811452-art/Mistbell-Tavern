package com.mistbell.tavern.v3.feature.chat

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Pure parsing helpers for OpenAI-compatible completion payloads and relay-specific variants.
 * Kept outside the HTTP client so the request class stays within the function-count limit.
 */
internal object ChatCompletionParser {

    fun completionText(root: JSONObject): String? {
        val first = root.optJSONArray("choices")?.optJSONObject(0)
        val candidateParts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val value = first?.optJSONObject("message")?.opt("content")
            ?: first?.optJSONObject("delta")?.opt("content")
            ?: first?.opt("text")
            ?: candidateParts
            ?: root.opt("content")
        return when (value) {
            null -> root.optString("text").takeIf(String::isNotEmpty)
            is String -> value
            else -> textOf(value)
        }
    }

    fun reasoningText(root: JSONObject): String? {
        val first = root.optJSONArray("choices")?.optJSONObject(0)
        val value = first?.optJSONObject("message")?.opt("reasoning_content")
            ?: first?.optJSONObject("delta")?.opt("reasoning_content")
        return textOf(value)
    }

    fun providerErrorMessage(root: JSONObject?): String? {
        val error = root?.opt("error") ?: return null
        val message = when (error) {
            is JSONObject -> error.optString("message")
                .takeIf(String::isNotBlank)
                ?: error.optString("type").takeIf(String::isNotBlank)
            is String -> error.takeIf(String::isNotBlank)
            else -> null
        }
        return (message ?: "Chat provider returned an error without a message.")
            .take(MAX_ERROR_MESSAGE_CHARS)
    }

    fun isHtmlResponse(contentTypeHeader: String?, payload: String?): Boolean =
        contentTypeHeader?.contains("text/html", ignoreCase = true) == true ||
            payload?.trimStart()?.startsWith("<") == true

    fun htmlCompletionMessage(
        statusCode: Int,
        contentTypeHeader: String?,
        payload: String?,
    ): String {
        val bytes = payload?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val title = htmlTitle(payload)?.let { "; title=$it" }.orEmpty()
        return "Chat provider returned an HTML page instead of JSON " +
            "(status=$statusCode; type=${contentTypeLabel(contentTypeHeader)}; bytes=$bytes$title). " +
            "Check the base URL and endpoint path."
    }

    fun malformedCompletionMessage(
        statusCode: Int,
        contentTypeHeader: String?,
        payload: String?,
        root: JSONObject?,
    ): String {
        val bytes = payload?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val keys = root?.keys()
            ?.asSequence()
            ?.take(MAX_DIAGNOSTIC_KEYS)
            ?.joinToString(",")
            .orEmpty()
            .ifEmpty { "unparsed" }
        return "Chat provider returned malformed completion data " +
            "(status=$statusCode; type=${contentTypeLabel(contentTypeHeader)}; bytes=$bytes; keys=$keys)."
    }

    fun contentTypeLabel(value: String?): String =
        value?.substringBefore(';')?.trim().orEmpty().ifEmpty { "unknown" }

    fun endpointLabel(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return "unknown"
        return "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
    }

    private fun textOf(value: Any?): String? = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val part = value.opt(index)
                val text = if (part is JSONObject) part.optString("text") else part as? String
                if (!text.isNullOrEmpty()) append(text)
            }
        }.takeIf(String::isNotEmpty)
        is JSONObject -> value.optString("text").takeIf(String::isNotEmpty)
            ?: textOf(value.optJSONArray("parts"))
        else -> null
    }

    private fun htmlTitle(payload: String?): String? {
        val match = HTML_TITLE.find(payload.orEmpty()) ?: return null
        return match.groupValues.getOrNull(1)
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.take(MAX_HTML_TITLE_CHARS)
            ?.takeIf(String::isNotEmpty)
    }

    private const val MAX_DIAGNOSTIC_KEYS = 6
    private const val MAX_ERROR_MESSAGE_CHARS = 300
    private const val MAX_HTML_TITLE_CHARS = 120
    private val HTML_TITLE = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val WHITESPACE = Regex("\\s+")
}
