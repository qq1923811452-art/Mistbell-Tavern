package com.mistbell.tavern.android.core.cache

/**
 * Cache lifecycle policy for Mistbell 2.0.
 *
 * Provides a shared place for cache expiration and size limits so
 * prompt/context/vector caches can use the same rules later.
 */
data class CachePolicy(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    fun isExpired(createdAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - createdAt > ttlMillis

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 256
        private const val DEFAULT_TTL_MINUTES = 30L
        private const val MILLIS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val DEFAULT_TTL_MILLIS =
            DEFAULT_TTL_MINUTES * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
    }
}
