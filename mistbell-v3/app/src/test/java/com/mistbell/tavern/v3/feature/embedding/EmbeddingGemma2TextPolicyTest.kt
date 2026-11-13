package com.mistbell.tavern.v3.feature.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingGemma2TextPolicyTest {
    @Test
    fun searchQueryUsesTheRetrievalQueryInstruction() {
        assertEquals(
            "task: search result | query: What happened last night?",
            EmbeddingGemma2TextPolicy.formatSearchQuery("What happened last night?"),
        )
    }

    @Test
    fun documentUsesTitleAndTextFieldsAndDefaultsTitleToNone() {
        assertEquals(
            "title: none | text: The tavern was closed.",
            EmbeddingGemma2TextPolicy.formatDocument("The tavern was closed."),
        )
        assertEquals(
            "title: Chapter 4 | text: The tavern was closed.",
            EmbeddingGemma2TextPolicy.formatDocument("The tavern was closed.", " Chapter 4 "),
        )
    }

    @Test
    fun truncatesInputAtTwoThousandCharsWithoutSplittingSurrogatePairs() {
        val longText = "a".repeat(EmbeddingGemma2TextPolicy.MAX_EMBED_CHARS) + "extra"
        assertEquals(2_000, EmbeddingGemma2TextPolicy.truncateInput(longText).length)
        val emojiAtBoundary = "a".repeat(1_999) + "😀" + "tail"
        val truncated = EmbeddingGemma2TextPolicy.truncateInput(emojiAtBoundary)
        assertEquals(1_999, truncated.length)
        assertFalse(Character.isSurrogate(truncated.last()))
        assertEquals("中".repeat(2_000), EmbeddingGemma2TextPolicy.truncateInput("中".repeat(2_001)))
    }

    @Test
    fun truncatesFormattedInputByUtf8BytesWithoutSplittingSurrogatePairs() {
        val emojiAtBoundary = "a".repeat(2_046) + "😀" + "tail"
        val truncated = EmbeddingGemma2TextPolicy.truncateFormattedInput(emojiAtBoundary)
        assertEquals(2_046, truncated.length)
        assertFalse(Character.isSurrogate(truncated.last()))
        val chinese = EmbeddingGemma2TextPolicy.truncateFormattedInput("中".repeat(2_000))
        assertEquals(2_046, chinese.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun formattersRespectWholeFormattedInputByteBudget() {
        val query = EmbeddingGemma2TextPolicy.formatSearchQuery("中".repeat(2_000))
        val document = EmbeddingGemma2TextPolicy.formatDocument("文".repeat(2_000), "题".repeat(1_000))
        assertTrue(query.toByteArray(Charsets.UTF_8).size <= EmbeddingGemma2TextPolicy.MAX_SAFE_FORMATTED_INPUT_BYTES)
        assertTrue(document.startsWith("title: "))
        assertTrue(
            document.toByteArray(Charsets.UTF_8).size <=
                EmbeddingGemma2TextPolicy.MAX_SAFE_FORMATTED_INPUT_BYTES,
        )
        assertTrue(document.substringAfter(" | text: ").toByteArray(Charsets.UTF_8).size >= 500)
        assertEquals(2, EmbeddingGemma2TextPolicy.POLICY_VERSION)
    }

    @Test
    fun supportedDimensionTiersNeverExceedNativeModelOutput() {
        assertEquals(setOf(128, 256, 512, 768), EmbeddingGemma2TextPolicy.supportedDimensions)
        assertTrue(
            EmbeddingGemma2TextPolicy.supportedDimensions.all {
                it <= EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS
            },
        )
        assertFalse(1024 in EmbeddingGemma2TextPolicy.supportedDimensions)
        assertFalse(1536 in EmbeddingGemma2TextPolicy.supportedDimensions)
        assertFalse(2048 in EmbeddingGemma2TextPolicy.supportedDimensions)
    }

    @Test
    fun truncatedVectorsAreRenormalized() {
        val vector = List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }
        val result = EmbeddingGemma2TextPolicy.truncateAndNormalize(vector, 128)

        assertEquals(128, result.size)
        assertTrue(kotlin.math.abs(result.sumOf { it.toDouble() * it.toDouble() } - 1.0) < 1e-5)
    }

    @Test
    fun unsupportedDimensionsAndInvalidVectorsAreRejected() {
        val vector = List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }
        for (dimension in listOf(300, 1024, 1536, 2048)) {
            assertTrue(
                runCatching {
                    EmbeddingGemma2TextPolicy.truncateAndNormalize(vector, dimension)
                }.isFailure,
            )
        }
        assertTrue(
            runCatching {
                EmbeddingGemma2TextPolicy.truncateAndNormalize(List(10) { 1f }, 128)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                EmbeddingGemma2TextPolicy.truncateAndNormalize(
                    List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 0f },
                    128,
                )
            }.isFailure,
        )
    }
}
