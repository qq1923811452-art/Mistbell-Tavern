package com.mistbell.tavern.v3.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun retriesEveryTransientFailureWithinTheBudget() {
        RETRYABLE_FAILURES.forEach { failure ->
            assertTrue(
                "$failure must be retried after the initial failure",
                RetryPolicy.shouldRetry(0, failure),
            )
            assertTrue(
                "$failure must be retried after the first retry",
                RetryPolicy.shouldRetry(1, failure),
            )
        }
    }

    @Test
    fun stopsRetryingOnceTheAttemptBudgetIsExhausted() {
        RETRYABLE_FAILURES.forEach { failure ->
            assertFalse(
                "$failure must not be retried after ${RetryPolicy.MAX_RETRIES} attempts",
                RetryPolicy.shouldRetry(RetryPolicy.MAX_RETRIES, failure),
            )
        }
    }

    @Test
    fun neverRetriesTerminalFailures() {
        TERMINAL_FAILURES.forEach { failure ->
            assertFalse(
                "$failure must never be retried",
                RetryPolicy.shouldRetry(attempt = 0, failure = failure),
            )
        }
    }

    @Test
    fun rejectsNegativeAttempts() {
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy.shouldRetry(attempt = -1, failure = FailureKind.NETWORK)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy.nextDelayMillis(attempt = -1)
        }
    }

    @Test
    fun appliesExponentialBackoffWithInjectedJitter() {
        assertEquals(FIRST_BASE_DELAY_MILLIS, RetryPolicy.nextDelayMillis(0, jitterMillis = 0L))
        assertEquals(SECOND_BASE_DELAY_MILLIS, RetryPolicy.nextDelayMillis(1, jitterMillis = 0L))
        assertEquals(
            FIRST_BASE_DELAY_MILLIS + MAX_JITTER_MILLIS,
            RetryPolicy.nextDelayMillis(0, jitterMillis = MAX_JITTER_MILLIS),
        )
        assertEquals(
            SECOND_BASE_DELAY_MILLIS + MAX_JITTER_MILLIS,
            RetryPolicy.nextDelayMillis(1, jitterMillis = MAX_JITTER_MILLIS),
        )
        assertEquals(
            FIRST_BASE_DELAY_MILLIS + MID_RANGE_JITTER_MILLIS,
            RetryPolicy.nextDelayMillis(0, jitterMillis = MID_RANGE_JITTER_MILLIS),
        )
    }

    @Test
    fun keepsRandomJitterInsideTheDocumentedRange() {
        repeat(JITTER_SAMPLE_COUNT) {
            assertTrue(
                "Attempt 0 delay was outside 500-750 ms",
                RetryPolicy.nextDelayMillis(0) in FIRST_BASE_DELAY_MILLIS..FIRST_MAX_DELAY_MILLIS,
            )
            assertTrue(
                "Attempt 1 delay was outside 1500-1750 ms",
                RetryPolicy.nextDelayMillis(1) in SECOND_BASE_DELAY_MILLIS..SECOND_MAX_DELAY_MILLIS,
            )
        }
    }

    @Test
    fun rejectsDelaysOutsideTheRetryBudget() {
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy.nextDelayMillis(attempt = RetryPolicy.MAX_RETRIES)
        }
    }

    @Test
    fun rejectsJitterOutsideTheAllowedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy.nextDelayMillis(attempt = 0, jitterMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy.nextDelayMillis(attempt = 0, jitterMillis = MAX_JITTER_MILLIS + 1L)
        }
    }

    @Test
    fun exposesTheConfiguredLimits() {
        assertEquals(2, RetryPolicy.MAX_RETRIES)
        assertEquals(MAX_JITTER_MILLIS, RetryPolicy.MAX_JITTER_MILLIS)
    }

    private companion object {
        const val FIRST_BASE_DELAY_MILLIS = 500L
        const val SECOND_BASE_DELAY_MILLIS = 1_500L
        const val MAX_JITTER_MILLIS = 250L
        const val MID_RANGE_JITTER_MILLIS = 120L
        const val FIRST_MAX_DELAY_MILLIS = FIRST_BASE_DELAY_MILLIS + MAX_JITTER_MILLIS
        const val SECOND_MAX_DELAY_MILLIS = SECOND_BASE_DELAY_MILLIS + MAX_JITTER_MILLIS
        const val JITTER_SAMPLE_COUNT = 1_000

        val RETRYABLE_FAILURES = listOf(
            FailureKind.NETWORK,
            FailureKind.TIMEOUT,
            FailureKind.RATE_LIMIT,
            FailureKind.SERVER_ERROR,
        )
        val TERMINAL_FAILURES = listOf(
            FailureKind.CLIENT_ERROR,
            FailureKind.CONFIGURATION,
            FailureKind.CANCELLED,
        )
    }
}
