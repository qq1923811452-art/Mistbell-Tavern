package com.mistbell.tavern.android.core.cache

import java.util.LinkedHashMap

/**
 * Bounded, thread-safe LRU cache with optional absolute expiry timestamps.
 */
class CacheManager<K, V>(
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val capacity = maxEntries.coerceAtLeast(1)
    private val lock = Any()
    private val cache =
        object : LinkedHashMap<K, CacheEntry<V>>(capacity + 1, CACHE_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean = size > capacity
        }
    private val stats = CacheStats()

    fun get(key: K): V? =
        synchronized(lock) {
            val entry = cache[key]
            if (entry == null || entry.isExpired()) {
                if (entry != null) cache.remove(key)
                stats.recordMiss()
                null
            } else {
                stats.recordHit()
                entry.value
            }
        }

    /** [expiresAt] is an absolute Unix timestamp in milliseconds; null means no expiry. */
    fun put(
        key: K,
        value: V,
        expiresAt: Long? = null,
    ) {
        synchronized(lock) {
            cache[key] = CacheEntry(key = key.toString(), value = value, expiresAt = expiresAt)
            stats.recordWrite()
        }
    }

    fun remove(key: K) {
        synchronized(lock) { cache.remove(key) }
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    fun getStats(): CacheStats = stats

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 512
        private const val CACHE_LOAD_FACTOR = 0.75f
    }
}
