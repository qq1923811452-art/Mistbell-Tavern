package com.mistbell.tavern.android.data.api

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientRetryPolicyTest {
    @Test
    fun `5xx status codes are transient server errors`() {
        listOf(500, 502, 503, 599).forEach { statusCode ->
            assertTrue(
                "Expected HTTP $statusCode to be transient",
                LlmClient.isTransientServerError(statusCode),
            )
        }
    }

    @Test
    fun `cancellation is never classified as retryable even when message contains 429`() {
        assertFalse(LlmClient.isRetryableException(CancellationException("cancelled after HTTP 429")))
    }

    @Test
    fun `network IO errors remain retryable`() {
        assertTrue(LlmClient.isRetryableException(IOException("connection reset")))
    }

    @Test
    fun `non 5xx status codes are not transient server errors`() {
        listOf(400, 429, 499, 600).forEach { statusCode ->
            assertFalse(
                "Expected HTTP $statusCode not to be a transient server error",
                LlmClient.isTransientServerError(statusCode),
            )
        }
    }
}
