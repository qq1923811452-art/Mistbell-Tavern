package com.mistbell.tavern.v3.core.network

import kotlin.random.Random

/**
 * Minimal, transport-agnostic classification of a failed chat request.
 *
 * The network boundary maps its concrete failure to one of these values before asking
 * [RetryPolicy]. This keeps the policy independent of OkHttp types. The expected mapping is:
 *
 * - `IOException` or an equivalent transport failure -> [NETWORK]
 * - request/read timeout -> [TIMEOUT]
 * - HTTP 429 -> [RATE_LIMIT]
 * - HTTP 5xx -> [SERVER_ERROR]
 * - any other HTTP 4xx -> [CLIENT_ERROR]
 * - missing or invalid provider configuration -> [CONFIGURATION]
 * - coroutine cancellation -> [CANCELLED]
 */
enum class FailureKind {
    NETWORK,
    TIMEOUT,
    RATE_LIMIT,
    SERVER_ERROR,
    CLIENT_ERROR,
    CONFIGURATION,
    CANCELLED,
}

/**
 * Bounded exponential-backoff policy for one chat request.
 *
 * [attempt] is zero-based: 0 means the initial attempt already failed, 1 means the first retry
 * failed, and an attempt equal to [MAX_RETRIES] means the retry budget is exhausted. A request
 * therefore runs at most three times: the initial attempt plus two retries.
 *
 * [FailureKind.NETWORK], [FailureKind.TIMEOUT], [FailureKind.RATE_LIMIT], and
 * [FailureKind.SERVER_ERROR] are retryable. Other 4xx responses ([FailureKind.CLIENT_ERROR]),
 * configuration errors, and cancellation are terminal. Cancellation must still be rethrown by
 * the caller; this policy only states that it must not be retried.
 */
object RetryPolicy {
    const val MAX_RETRIES = 2
    const val MAX_JITTER_MILLIS = 250L

    private const val FIRST_RETRY_DELAY_MILLIS = 500L
    private const val SECOND_RETRY_DELAY_MILLIS = 1_500L
    private const val MIN_JITTER_MILLIS = 0L
    private const val JITTER_UPPER_BOUND_MILLIS = MAX_JITTER_MILLIS + 1L
    private val BASE_DELAYS_MILLIS = longArrayOf(
        FIRST_RETRY_DELAY_MILLIS,
        SECOND_RETRY_DELAY_MILLIS,
    )
    private val RETRYABLE_FAILURES = setOf(
        FailureKind.NETWORK,
        FailureKind.TIMEOUT,
        FailureKind.RATE_LIMIT,
        FailureKind.SERVER_ERROR,
    )

    /**
     * Returns whether another attempt is allowed after [attempt] failures caused by [failure].
     *
     * @throws IllegalArgumentException when [attempt] is negative.
     */
    fun shouldRetry(attempt: Int, failure: FailureKind): Boolean {
        require(attempt >= 0) { "Attempt must not be negative." }
        return attempt < MAX_RETRIES && failure in RETRYABLE_FAILURES
    }

    /**
     * Returns the backoff before the next attempt after [attempt] failures, using a random
     * jitter in `0..MAX_JITTER_MILLIS` inclusive. Attempt 0 yields 500-750 ms and attempt 1
     * yields 1500-1750 ms.
     *
     * @throws IllegalArgumentException when [attempt] is outside the retry budget.
     */
    fun nextDelayMillis(attempt: Int): Long =
        nextDelayMillis(attempt, Random.nextLong(MIN_JITTER_MILLIS, JITTER_UPPER_BOUND_MILLIS))

    /**
     * Deterministic overload for callers and tests that inject their own jitter.
     *
     * @throws IllegalArgumentException when [attempt] is outside the retry budget or
     * [jitterMillis] is outside `0..MAX_JITTER_MILLIS`.
     */
    fun nextDelayMillis(attempt: Int, jitterMillis: Long): Long {
        require(attempt in BASE_DELAYS_MILLIS.indices) { "Attempt is outside the retry budget." }
        require(jitterMillis in MIN_JITTER_MILLIS..MAX_JITTER_MILLIS) {
            "Jitter must be between $MIN_JITTER_MILLIS and $MAX_JITTER_MILLIS milliseconds."
        }
        return BASE_DELAYS_MILLIS[attempt] + jitterMillis
    }
}
