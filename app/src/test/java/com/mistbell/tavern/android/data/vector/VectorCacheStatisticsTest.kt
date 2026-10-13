package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorCacheStatisticsTest {
    @Test
    fun `empty cache has zero lookups and zero hit rate`() {
        val stats = VectorCacheStatistics(0, 0, 0, 0, 0)

        assertEquals(0L, stats.totalLookups)
        assertEquals(0.0, stats.hitRate, 0.0)
    }

    @Test
    fun `hit rate is computed from cache lookups only`() {
        val stats = VectorCacheStatistics(
            hitCount = 3,
            missCount = 7,
            writeCount = 9,
            invalidationCount = 2,
            entryCount = 4,
        )

        assertEquals(10L, stats.totalLookups)
        assertEquals(0.3, stats.hitRate, 1e-9)
    }

    @Test
    fun `all cache lookups hit gives one hundred percent hit rate`() {
        val stats = VectorCacheStatistics(5, 0, 2, 1, 2)

        assertEquals(5L, stats.totalLookups)
        assertEquals(1.0, stats.hitRate, 0.0)
    }
}
