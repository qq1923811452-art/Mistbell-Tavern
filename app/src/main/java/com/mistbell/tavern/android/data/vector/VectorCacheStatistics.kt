package com.mistbell.tavern.android.data.vector

/**
 * Immutable snapshot of the live vector search cache.
 *
 * [hitRate] is based only on cache lookups: hits / (hits + misses).
 * Writes and invalidations are reported separately and do not affect the rate.
 */
data class VectorCacheStatistics(
    val hitCount: Long,
    val missCount: Long,
    val writeCount: Long,
    val invalidationCount: Long,
    val entryCount: Int,
) {
    val totalLookups: Long
        get() = hitCount + missCount

    val hitRate: Double
        get() = if (totalLookups == 0L) 0.0 else hitCount.toDouble() / totalLookups.toDouble()
}
