package com.mistbell.tavern.android.core.cache

/**
 * Mistbell 2.0 unified cache manager.
 *
 * Provides a lightweight cache layer for prompt/context/vector cache.
 */
class CacheManager<K, V> {
    private val cache = mutableMapOf<K, CacheEntry<V>>()
    private val stats = CacheStats()

    fun get(key: K): V? {
        val entry = cache[key]
        return if (entry != null) {
            stats.recordHit()
            entry.value
        } else {
            stats.recordMiss()
            null
        }
    }

    fun put(key: K, value: V) {
        cache[key] = CacheEntry(key = key.toString(), value = value)
        stats.recordWrite()
    }

    fun remove(key: K) {
        cache.remove(key)
    }

    fun clear() {
        cache.clear()
    }

    fun getStats(): CacheStats = stats
}
