package com.mistbell.tavern.android.core.cache

/**
 * Cache statistics for Mistbell 2.0.
 *
 * Tracks real cache behavior instead of only displaying estimated values.
 */
data class CacheStats(
    val hitCount: Long = 0,
    val missCount: Long = 0,
    val writeCount: Long = 0
) {
    val totalRequests: Long
        get() = hitCount + missCount

    val hitRate: Float
        get() = if (totalRequests == 0L) 0f else hitCount.toFloat() / totalRequests.toFloat()
}
