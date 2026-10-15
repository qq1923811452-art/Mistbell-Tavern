package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIEmbeddingResponseValidationTest {
    @Test
    fun `out of order indexes are restored to input order`() {
        assertEquals(listOf(1, 0, 2), validateEmbeddingResponseOrder(listOf(1, 0, 2), expectedCount = 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate indexes are rejected`() {
        validateEmbeddingResponseOrder(listOf(0, 0), expectedCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing index is rejected even when response count matches`() {
        validateEmbeddingResponseOrder(listOf(0, 2), expectedCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative index is rejected`() {
        validateEmbeddingResponseOrder(listOf(-1, 1), expectedCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of range index is rejected`() {
        validateEmbeddingResponseOrder(listOf(1, 2), expectedCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `count mismatch is rejected`() {
        validateEmbeddingResponseOrder(listOf(0), expectedCount = 2)
    }
}
