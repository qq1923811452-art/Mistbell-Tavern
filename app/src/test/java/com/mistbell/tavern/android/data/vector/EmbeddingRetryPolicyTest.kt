package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingRetryPolicyTest {
    @Test
    fun `rate limit is retryable`() {
        assertTrue(isRetryableEmbeddingStatusCode(429))
    }

    @Test
    fun `server errors are retryable`() {
        assertTrue(isRetryableEmbeddingStatusCode(500))
        assertTrue(isRetryableEmbeddingStatusCode(599))
    }

    @Test
    fun `client errors are not retryable`() {
        assertFalse(isRetryableEmbeddingStatusCode(400))
        assertFalse(isRetryableEmbeddingStatusCode(401))
        assertFalse(isRetryableEmbeddingStatusCode(404))
    }

    @Test
    fun `non error status codes are not retryable`() {
        assertFalse(isRetryableEmbeddingStatusCode(200))
        assertFalse(isRetryableEmbeddingStatusCode(600))
    }
}
