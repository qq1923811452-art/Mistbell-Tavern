package com.mistbell.tavern.v3.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRichTextTest {
    @Test
    fun splitsClosedThinkBlocksAndKeepsVisibleBody() {
        val (thinking, body) =
            ChatMessageRichText.splitThinking("<think>plan</think>Hello <THINK>more</THINK>world")

        assertEquals("plan\nmore", thinking)
        assertEquals("Hello world", body)
    }

    @Test
    fun splitsTrailingUnclosedThinkBlock() {
        val (thinking, body) = ChatMessageRichText.splitThinking("Visible <think>still reasoning")

        assertEquals("still reasoning", thinking)
        assertEquals("Visible", body)
    }

    @Test
    fun plainTextHasNoThinkingBlock() {
        val (thinking, body) = ChatMessageRichText.splitThinking("普通文本")

        assertNull(thinking)
        assertEquals("普通文本", body)
    }

    @Test
    fun formatsBoldItalicAndCodeWithoutChangingVisibleLetters() {
        val formatted = ChatMessageRichText.formatMarkdown("**bold** *soft* `code`")

        assertEquals("bold soft code", formatted.text)
        val styles = formatted.spanStyles
        assertTrue(styles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(
            styles.any {
                it.item.fontStyle == FontStyle.Italic && it.item.color == Color(0xFFD8B4FE)
            },
        )
        assertTrue(styles.any { it.item.fontFamily == FontFamily.Monospace })
        val bold = styles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bold", formatted.text.substring(bold.start, bold.end))
        val code = styles.first { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("code", formatted.text.substring(code.start, code.end))
    }

    @Test
    fun leavesUnpairedMarkersAndMultilineItalicIntact() {
        assertEquals("**open", ChatMessageRichText.formatMarkdown("**open").text)
        assertEquals("*a\nb*", ChatMessageRichText.formatMarkdown("*a\nb*").text)
    }
}
