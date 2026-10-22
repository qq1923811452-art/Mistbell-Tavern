package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPreviewBuilderTest {

    private fun hasUnpairedSurrogate(text: String): Boolean = text.withIndex().any { (index, char) ->
        val highWithoutLow = char.isHighSurrogate() &&
            (index + 1 >= text.length || !text[index + 1].isLowSurrogate())
        val lowWithoutHigh = char.isLowSurrogate() &&
            (index == 0 || !text[index - 1].isHighSurrogate())
        highWithoutLow || lowWithoutHigh
    }

    @Test
    fun buildsOneLabeledSectionPerMessage() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, "System rules."),
            PromptMessage(PromptRole.USER, "Hello"),
            PromptMessage(PromptRole.ASSISTANT, "Hi there"),
        )
        val preview = PromptPreviewBuilder.build(messages)
        assertEquals(3, preview.sections.size)
        assertEquals(listOf("#1 SYSTEM", "#2 USER", "#3 ASSISTANT"), preview.sections.map { it.label })
        assertEquals(listOf("SYSTEM", "USER", "ASSISTANT"), preview.sections.map { it.role })
        assertEquals(listOf("System rules.", "Hello", "Hi there"), preview.sections.map { it.preview })
        assertEquals(listOf(13, 5, 8), preview.sections.map { it.chars })
        assertEquals(26, preview.totalChars)
    }

    @Test
    fun truncatesLongMessagesWithAMarkerButKeepsFullCounts() {
        val atBoundary = PromptPreviewBuilder.build(
            listOf(PromptMessage(PromptRole.USER, "12345678")),
            previewChars = 8,
        )
        val truncated = PromptPreviewBuilder.build(
            listOf(PromptMessage(PromptRole.USER, "abcdefghijklmnopqrst")),
            previewChars = 10,
        )
        assertEquals("12345678", atBoundary.sections.single().preview)
        assertEquals(8, atBoundary.sections.single().chars)
        assertEquals("abcdefghij…", truncated.sections.single().preview)
        assertEquals(20, truncated.sections.single().chars)
        assertEquals(20, truncated.totalChars)
    }

    @Test
    fun neverSplitsASurrogatePairWhenTruncating() {
        val emojiFirst = PromptPreviewBuilder.build(
            listOf(PromptMessage(PromptRole.USER, "😀😀")),
            previewChars = 1,
        )
        val boundaryAfterHigh = PromptPreviewBuilder.build(
            listOf(PromptMessage(PromptRole.USER, "a😀bc")),
            previewChars = 2,
        )
        assertEquals("…", emojiFirst.sections.single().preview)
        assertEquals(4, emojiFirst.sections.single().chars)
        assertEquals("a…", boundaryAfterHigh.sections.single().preview)
        assertEquals(5, boundaryAfterHigh.sections.single().chars)
        assertFalse(hasUnpairedSurrogate(emojiFirst.sections.single().preview))
        assertFalse(hasUnpairedSurrogate(boundaryAfterHigh.sections.single().preview))
    }

    @Test
    fun emptyMessagesYieldAnEmptyPreview() {
        val preview = PromptPreviewBuilder.build(emptyList())
        assertTrue(preview.sections.isEmpty())
        assertEquals(0, preview.totalChars)
    }

    @Test
    fun zeroPreviewCharsYieldsEmptyPreviewsButKeepsCounts() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, "Rules"),
            PromptMessage(PromptRole.USER, "Hi"),
        )
        val preview = PromptPreviewBuilder.build(messages, previewChars = 0)
        assertEquals(listOf("", ""), preview.sections.map { it.preview })
        assertEquals(listOf(5, 2), preview.sections.map { it.chars })
        assertEquals(7, preview.totalChars)
    }

    @Test
    fun negativePreviewCharsIsTreatedAsZeroInsteadOfThrowing() {
        val preview = PromptPreviewBuilder.build(
            listOf(PromptMessage(PromptRole.ASSISTANT, "A long enough reply")),
            previewChars = -5,
        )
        assertEquals("", preview.sections.single().preview)
        assertEquals(19, preview.sections.single().chars)
    }

    @Test
    fun longContentUsesTheDefaultPreviewSize() {
        val content = "x".repeat(500)
        val preview = PromptPreviewBuilder.build(listOf(PromptMessage(PromptRole.ASSISTANT, content)))
        val section = preview.sections.single()
        assertEquals(500, section.chars)
        assertEquals(500, preview.totalChars)
        assertEquals(PromptPreviewBuilder.DEFAULT_PREVIEW_CHARS, section.preview.length - 1)
        assertEquals("x".repeat(PromptPreviewBuilder.DEFAULT_PREVIEW_CHARS) + "…", section.preview)
    }

    @Test
    fun emptySystemMessageKeepsAZeroCharacterSection() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, ""),
            PromptMessage(PromptRole.USER, "Hi"),
        )
        val preview = PromptPreviewBuilder.build(messages)
        assertEquals(listOf("#1 SYSTEM", "#2 USER"), preview.sections.map { it.label })
        assertEquals(0, preview.sections.first().chars)
        assertEquals("", preview.sections.first().preview)
        assertEquals(2, preview.totalChars)
    }
}
