package com.mistbell.tavern.v3.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Pure thinking-block splitting and lightweight Markdown styling for chat bubbles. */
object ChatMessageRichText {
    private const val BOLD_MARKER_LENGTH = 2
    private const val SINGLE_MARKER_LENGTH = 1
    private const val ITALIC_RED = 0xD8
    private const val ITALIC_GREEN = 0xB4
    private const val ITALIC_BLUE = 0xFE
    private val THINK_CLOSED =
        Regex("<think>(.*?)</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val THINK_OPEN = Regex("<think>(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
    private val ITALIC = Regex("\\*(?!\\*)([^*\\n]+?)\\*")
    private val CODE = Regex("`([^`\\n]+?)`")
    private val BOLD_STYLE = SpanStyle(fontWeight = FontWeight.Bold)
    private val ITALIC_STYLE =
        SpanStyle(
            fontStyle = FontStyle.Italic,
            color = Color(ITALIC_RED, ITALIC_GREEN, ITALIC_BLUE),
        )
    private val CODE_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)

    /** Returns merged reasoning text (closed and trailing unclosed blocks) and the visible body. */
    fun splitThinking(raw: String): Pair<String?, String> {
        if (!raw.contains("<think>", ignoreCase = true)) return null to raw
        val thinkingParts = mutableListOf<String>()
        var body =
            THINK_CLOSED.replace(raw) { match ->
                thinkingParts += match.groupValues[1].trim()
                ""
            }
        THINK_OPEN.find(body)?.let { match ->
            thinkingParts += match.groupValues[1].trim()
            body = body.removeRange(match.range)
        }
        val thinking = thinkingParts.filter(String::isNotBlank).joinToString("\n").takeIf(String::isNotBlank)
        return thinking to body.trim()
    }

    /** Styles `**bold**`, `*italic/action*` and `` `code` `` without changing the visible letters. */
    fun formatMarkdown(text: String): AnnotatedString {
        val builder = AnnotatedString.Builder()
        var cursor = 0
        while (true) {
            val segment = nextSegment(text, cursor) ?: break
            if (segment.start > cursor) builder.append(text.substring(cursor, segment.start))
            val styleStart = builder.length
            builder.append(text.substring(segment.contentStart, segment.contentEnd))
            builder.addStyle(segment.style, styleStart, builder.length)
            cursor = segment.end
        }
        if (cursor < text.length) builder.append(text.substring(cursor))
        return builder.toAnnotatedString()
    }

    private fun nextSegment(
        text: String,
        from: Int,
    ): StyledSegment? =
        listOfNotNull(
            BOLD.find(text, from)?.let { segment(it, BOLD_MARKER_LENGTH, BOLD_STYLE) },
            ITALIC.find(text, from)?.let { segment(it, SINGLE_MARKER_LENGTH, ITALIC_STYLE) },
            CODE.find(text, from)?.let { segment(it, SINGLE_MARKER_LENGTH, CODE_STYLE) },
        ).minByOrNull { it.start }

    private fun segment(
        match: MatchResult,
        markerLength: Int,
        style: SpanStyle,
    ): StyledSegment =
        StyledSegment(
            start = match.range.first,
            end = match.range.last + 1,
            contentStart = match.range.first + markerLength,
            contentEnd = match.range.last + 1 - markerLength,
            style = style,
        )
}

private data class StyledSegment(
    val start: Int,
    val end: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val style: SpanStyle,
)
