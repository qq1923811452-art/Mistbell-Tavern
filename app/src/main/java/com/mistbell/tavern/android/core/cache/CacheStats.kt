package com.mistbell.tavern.android.core.cache

/**
 * Cache statistics for Mistbell 2.0.
 *
 * Tracks real cache behavior instead of only displaying estimated values.
 */
class CacheStats {
    var hitCount: Long = 0
        private set
    var missCount: Long = 0
        private set
    var writeCount: Long = 0
        private set

    val totalRequests: Long
        get() = hitCount + missCount

    val hitRate: Float
        get() = if (totalRequests == 0L) 0f else hitCount.toFloat() / totalRequests.toFloat()

    fun recordHit() {
        hitCount++
    }

    fun recordMiss() {
        missCount++
    }

    fun recordWrite() {
        writeCount++
    }
}
