package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide cache for decoded memory source text.
 *
 * Every retrieval turn loads the same small text store twice (once to decide whether the index is
 * stale, once to inject memory). Each load previously re-read the file and re-verified every
 * record hash, which is proportional to the whole library. This cache keeps the decoded,
 * already-verified list so a turn pays that cost at most once.
 *
 * Correctness rules:
 *
 * - Entries are keyed by canonical path, an in-process write generation, length, and mtime, so a
 *   save through *any* store instance invalidates the entry immediately — including two saves that
 *   land in the same millisecond with an identical length.
 * - Only successfully decoded, hash-verified lists are cached. Missing or corrupt files always
 *   re-read the disk, so corruption is never remembered as valid data.
 * - Cached lists are read-only views, so a caller cannot mutate shared state.
 * - All instances for the same path share one lock, so readers and writers serialize across
 *   instances rather than only within one object.
 */
internal object MemoryTextMemoryCache {
    private const val MAX_ENTRIES = 4
    private const val LOAD_FACTOR = 0.75f

    data class Key(
        val path: String,
        val generation: Long,
        val length: Long,
        val lastModified: Long,
    )

    private val pathLocks = ConcurrentHashMap<String, Any>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val entries = object : LinkedHashMap<Key, List<MemoryTextRecord>>(MAX_ENTRIES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<MemoryTextRecord>>): Boolean =
            size > MAX_ENTRIES
    }

    /** One monitor per canonical path, shared by every store instance that touches the file. */
    fun lockFor(file: File): Any = pathLocks.computeIfAbsent(canonicalPath(file)) { Any() }

    fun key(baseFile: File, identityFile: File = baseFile): Key? {
        // The path always identifies the store (so invalidation matches); the identity file
        // supplies the freshness fields, because a recovery read may come from the `.bak` copy.
        val path = canonicalPath(baseFile)
        if (!identityFile.exists()) return null
        return Key(
            path = path,
            generation = generations.computeIfAbsent(path) { AtomicLong() }.get(),
            length = identityFile.length(),
            lastModified = identityFile.lastModified(),
        )
    }

    @Synchronized
    fun find(key: Key): List<MemoryTextRecord>? = entries[key]

    /**
     * Caches [records] and returns the stored read-only view, so the load that populated the cache
     * and every later load hand back the same instance. Callers therefore cannot observe a
     * difference between a cache miss and a cache hit.
     */
    @Synchronized
    fun store(key: Key, records: List<MemoryTextRecord>): List<MemoryTextRecord> {
        val snapshot = java.util.Collections.unmodifiableList(ArrayList(records))
        entries[key] = snapshot
        return snapshot
    }

    /** Drops every entry for [file] and advances its generation so in-flight keys go stale. */
    @Synchronized
    fun invalidate(file: File) {
        val path = canonicalPath(file)
        generations.computeIfAbsent(path) { AtomicLong() }.incrementAndGet()
        entries.keys.removeAll { it.path == path }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        generations.clear()
    }

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}
