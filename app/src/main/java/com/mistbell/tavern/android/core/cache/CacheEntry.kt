package com.mistbell.tavern.android.core.cache

/**
 * Common cache record for Mistbell 2.0.
 *
 * Keeps cache data independent from storage implementation so it can later
 * be backed by Room, DataStore or memory cache.
 */
data class CacheEntry<T>(
    val key: String,
    val value: T,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return expiresAt != null && now >= expiresAt
    }
}
