package com.mistbell.tavern.android.core.cache

/**
 * Cache lifecycle policy for Mistbell 2.0.
 *
 * Provides a shared place for cache expiration and size limits so
 * prompt/context/vector caches can use the same rules later.
 */
data class CachePolicy(
    val maxEntries: Int = 256,
    val ttlMillis: Long = 30 * 60 * 1000L
) {
    fun isExpired(createdAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        return now - createdAt > ttlMillis
    }
}
