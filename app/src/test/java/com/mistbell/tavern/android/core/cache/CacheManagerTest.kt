package com.mistbell.tavern.android.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CacheManagerTest {
    @Test
    fun `get records hits and misses without changing cached values`() {
        val manager = CacheManager<String, String>()
        val value = "cached prompt"
        manager.put("prompt", value)

        assertSame(value, manager.get("prompt"))
        assertNull(manager.get("missing"))

        val stats = manager.getStats()
        assertEquals(1L, stats.hitCount)
        assertEquals(1L, stats.missCount)
        assertEquals(2L, stats.totalRequests)
        assertEquals(0.5f, stats.hitRate, 0.0001f)
    }

    @Test
    fun `put counts writes and overwriting keeps one cache entry`() {
        val manager = CacheManager<String, String>()
        manager.put("prompt", "first")
        manager.put("prompt", "second")

        assertEquals("second", manager.get("prompt"))
        assertEquals(2L, manager.getStats().writeCount)
        assertEquals(1L, manager.getStats().hitCount)
    }

    @Test
    fun `remove and clear remove cached values`() {
        val manager = CacheManager<String, String>()
        manager.put("a", "one")
        manager.put("b", "two")

        manager.remove("a")
        assertNull(manager.get("a"))
        assertEquals("two", manager.get("b"))

        manager.clear()
        assertNull(manager.get("b"))
        assertEquals(1L, manager.getStats().hitCount)
        assertEquals(2L, manager.getStats().missCount)
    }

    @Test
    fun `expired entries are treated as misses`() {
        val manager = CacheManager<String, String>()
        manager.put("expired", "stale", expiresAt = 0L)

        assertNull(manager.get("expired"))
        assertEquals(0L, manager.getStats().hitCount)
        assertEquals(1L, manager.getStats().missCount)
    }

    @Test
    fun `least recently used entries are evicted at capacity`() {
        val manager = CacheManager<String, String>(maxEntries = 2)
        manager.put("a", "one")
        manager.put("b", "two")
        assertEquals("one", manager.get("a"))
        manager.put("c", "three")

        assertEquals("one", manager.get("a"))
        assertNull(manager.get("b"))
        assertEquals("three", manager.get("c"))
    }
}
