package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIEmbeddingResponseValidationTest {
    @Test
    fun `out of order indexes are normalized to input order`() {
        assertEquals(listOf(1, 0, 2), validateEmbeddingResponseOrder(listOf(1, 0, 2), expectedCount = 3))
    }

    @Test
    fun `fully unindexed response preserves response order`() {
        assertEquals(listOf(0, 1), validateEmbeddingResponseOrder(listOf(null, null), expectedCount = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate indexes are rejected`() {
        validateEmbeddingResponseOrder(listOf(0, 0), expectedCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing index is rejected even when response count matches`() {
        validateEmbeddingResponseOrder(listOf(0, 2), expectedCount = 2)
    }

    @Test(expected = IllegalStateException::class)
    fun `partially indexed response is rejected`() {
        validateEmbeddingResponseOrder(listOf(0, null), expectedCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `count mismatch is rejected`() {
        validateEmbeddingResponseOrder(listOf(0), expectedCount = 2)
    }
}
