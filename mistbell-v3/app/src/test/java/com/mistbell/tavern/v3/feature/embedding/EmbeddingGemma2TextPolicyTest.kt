package com.mistbell.tavern.v3.feature.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingGemma2TextPolicyTest {
    @Test
    fun searchQueryUsesTheRetrievalQueryInstruction() {
        assertEquals(
            "task: search query | text: What happened last night?",
            EmbeddingGemma2TextPolicy.formatSearchQuery("What happened last night?"),
        )
    }

    @Test
    fun documentUsesTitleAndTextFieldsAndDefaultsTitleToNone() {
        assertEquals(
            "task: search result | title: none | text: The tavern was closed.",
            EmbeddingGemma2TextPolicy.formatDocument("The tavern was closed."),
        )
        assertEquals(
            "task: search result | title: Chapter 4 | text: The tavern was closed.",
            EmbeddingGemma2TextPolicy.formatDocument("The tavern was closed.", " Chapter 4 "),
        )
    }

    @Test
    fun truncatedVectorsAreRenormalized() {
        val vector = List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }
        val result = EmbeddingGemma2TextPolicy.truncateAndNormalize(vector, 128)

        assertEquals(128, result.size)
        assertTrue(kotlin.math.abs(result.sumOf { it.toDouble() * it.toDouble() } - 1.0) < 1e-5)
    }

    @Test
    fun higherDimensionsAreListedButCannotBeFakedFromEmbeddingGemma2Output() {
        assertTrue(1024 in EmbeddingGemma2TextPolicy.supportedDimensions)
        assertTrue(1536 in EmbeddingGemma2TextPolicy.supportedDimensions)
        assertTrue(2048 in EmbeddingGemma2TextPolicy.supportedDimensions)
        val nativeVector = List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }
        for (dimension in listOf(1024, 1536, 2048)) {
            assertTrue(runCatching {
                EmbeddingGemma2TextPolicy.truncateAndNormalize(nativeVector, dimension)
            }.isFailure)
        }
    }

    @Test
    fun unsupportedDimensionsAndInvalidVectorsAreRejected() {
        val vector = List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }
        assertTrue(runCatching {
            EmbeddingGemma2TextPolicy.truncateAndNormalize(vector, 300)
        }.isFailure)
        assertTrue(runCatching {
            EmbeddingGemma2TextPolicy.truncateAndNormalize(List(10) { 1f }, 128)
        }.isFailure)
        assertTrue(runCatching {
            EmbeddingGemma2TextPolicy.truncateAndNormalize(
                List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 0f },
                128,
            )
        }.isFailure)
    }
}
