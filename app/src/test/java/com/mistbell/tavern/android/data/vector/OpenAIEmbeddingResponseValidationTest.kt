package com.mistbell.tavern.android.data.vector

import org.junit.Test

class OpenAIEmbeddingResponseValidationTest {
    @Test
    fun `out of order indexes are valid when the complete range is present`() {
        validateEmbeddingResponseIndices(listOf(1, 0, 2), expectedCount = 3)
    }

    @Test(expected = IllegalStateException::class)
    fun `duplicate indexes are rejected`() {
        validateEmbeddingResponseIndices(listOf(0, 0), expectedCount = 2)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing index is rejected even when response count matches`() {
        validateEmbeddingResponseIndices(listOf(0, 2), expectedCount = 2)
    }

    @Test(expected = IllegalStateException::class)
    fun `negative index is rejected`() {
        validateEmbeddingResponseIndices(listOf(-1, 1), expectedCount = 2)
    }

    @Test(expected = IllegalStateException::class)
    fun `out of range index is rejected`() {
        validateEmbeddingResponseIndices(listOf(1, 2), expectedCount = 2)
    }

    @Test(expected = IllegalStateException::class)
    fun `count mismatch is rejected`() {
        validateEmbeddingResponseIndices(listOf(0), expectedCount = 2)
    }
}
