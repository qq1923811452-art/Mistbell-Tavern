package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import java.io.File

/**
 * Process-wide cache for decoded vector snapshots.
 *
 * Snapshots stay authoritative on disk and are still validated when written; this only avoids
 * re-reading, re-hashing and re-decoding the identical file twice per message (preflight plus
 * retrieval). The key carries the file identity, so a rebuild or replacement produces a miss
 * instead of a stale index being reused.
 */
internal object VectorSnapshotMemoryCache {
    data class Key(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val modelId: String,
        val dimensions: Int,
    )

    private const val MAX_ENTRIES = 4
    private const val LOAD_FACTOR = 0.75f

    private val entries = object : LinkedHashMap<Key, VectorSnapshot>(MAX_ENTRIES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(oldest: MutableMap.MutableEntry<Key, VectorSnapshot>): Boolean =
            size > MAX_ENTRIES
    }

    fun find(file: File, key: Key): VectorSnapshot? = synchronized(entries) {
        val snapshot = entries[key] ?: return null
        val unchanged = file.isFile && file.length() == key.length && file.lastModified() == key.lastModified
        if (!unchanged) {
            entries.remove(key)
            return null
        }
        snapshot
    }

    fun store(key: Key, snapshot: VectorSnapshot) {
        synchronized(entries) {
            entries.keys.filter { it.path == key.path }.forEach(entries::remove)
            entries[key] = snapshot
        }
    }

    /** Test hook: a changed file must never be served from a previous case. */
    fun clear() = synchronized(entries) { entries.clear() }
}
