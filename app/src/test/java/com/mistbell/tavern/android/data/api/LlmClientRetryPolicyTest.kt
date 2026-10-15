package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

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
    fun `only failures known to happen before provider acceptance are retryable`() {
        assertTrue(LlmClient.isRetryableException(ConnectException("connection refused")))
        assertTrue(LlmClient.isRetryableException(UnknownHostException("DNS lookup failed")))
        assertTrue(LlmClient.isRetryableException(NoRouteToHostException("no route to host")))
    }

    @Test
    fun `ambiguous IO failures and socket timeouts are not retryable`() {
        assertFalse(LlmClient.isRetryableException(IOException("connection reset after request send")))
        assertFalse(LlmClient.isRetryableException(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `HTTP retry classification uses structured status codes`() {
        assertTrue(
            LlmClient.isRetryableException(
                LlmClient.ApiError(429, "Too Many Requests", thinkingParameterRejected = false),
            ),
        )
        assertTrue(
            LlmClient.isRetryableException(
                LlmClient.ApiError(503, "Service Unavailable", thinkingParameterRejected = false),
            ),
        )
        assertFalse(
            LlmClient.isRetryableException(
                LlmClient.ApiError(400, "Bad Request", thinkingParameterRejected = false),
            ),
        )
        assertFalse(LlmClient.isRetryableException(IllegalStateException("HTTP 429 Too Many Requests")))
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
