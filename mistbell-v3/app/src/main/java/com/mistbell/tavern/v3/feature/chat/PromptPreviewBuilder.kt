package com.mistbell.tavern.v3.feature.chat

/**
 * Builds a read-only preview of an assembled prompt for the chat debug surface.
 *
 * Every message becomes exactly one [Section], so the caller can show which role contributed how
 * many characters before a request is sent. This object is pure logic: it does not mutate the
 * input, log, persist, or call any model. Truncation counts UTF-16 code units but never splits a
 * surrogate pair, so emoji and other supplementary characters stay intact in the preview.
 */
object PromptPreviewBuilder {
    /** Characters shown per section when the caller does not request a different preview size. */
    const val DEFAULT_PREVIEW_CHARS = 200

    /** Marker appended to a preview when the message is longer than the requested size. */
    const val TRUNCATION_MARKER = "…"

    /** One message as `#index ROLE`, with its full character count and a bounded preview. */
    data class Section(val label: String, val role: String, val chars: Int, val preview: String)

    /** All sections in message order plus the summed character count of every message. */
    data class Preview(val sections: List<Section>, val totalChars: Int)

    /**
     * Returns one [Section] per message without ever throwing: a non-positive [previewChars] is
     * treated as zero, and an empty [messages] list yields an empty [Preview].
     */
    fun build(messages: List<PromptMessage>, previewChars: Int = DEFAULT_PREVIEW_CHARS): Preview {
        val limit = previewChars.coerceAtLeast(0)
        val sections = messages.mapIndexed { index, message ->
            val content = message.content
            Section(
                label = "#${index + 1} ${message.role.name}",
                role = message.role.name,
                chars = content.length,
                preview = previewOf(content, limit),
            )
        }
        return Preview(sections = sections, totalChars = messages.sumOf { it.content.length })
    }

    private fun previewOf(content: String, limit: Int): String = when {
        limit <= 0 -> ""
        content.length <= limit -> content
        else -> content.substring(0, truncationEnd(content, limit)) + TRUNCATION_MARKER
    }

    /**
     * End index for a truncated preview. A split high/low surrogate pair is dropped instead of
     * being emitted as a lone code unit.
     */
    private fun truncationEnd(content: String, limit: Int): Int =
        if (content[limit - 1].isHighSurrogate() && content[limit].isLowSurrogate()) limit - 1 else limit
}
