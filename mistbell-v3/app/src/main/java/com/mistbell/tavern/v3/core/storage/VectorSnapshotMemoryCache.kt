package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Process-wide decoded snapshots. Disk identity, not length/mtime alone, controls reuse. */
internal object VectorSnapshotMemoryCache {
    data class Identity(
        val fileKey: Any,
        val length: Long,
        val lastModified: FileTime,
        val created: FileTime,
    )

    data class Key(
        val path: String,
        val identity: Identity,
        val modelId: String,
        val dimensions: Int,
    )

    private const val MAX_ENTRIES = 4
    private const val LOAD_FACTOR = 0.75f

    // Retain locks for the process lifetime: eviction could split live stores onto different locks.
    private val pathLocks = HashMap<String, Any>()
    private val entries =
        object : LinkedHashMap<Key, VectorSnapshot>(MAX_ENTRIES, LOAD_FACTOR, true) {
            override fun removeEldestEntry(oldest: MutableMap.MutableEntry<Key, VectorSnapshot>): Boolean =
                size > MAX_ENTRIES
        }

    fun lockFor(file: File): Any =
        synchronized(pathLocks) {
            pathLocks.getOrPut(file.canonicalPath) { Any() }
        }

    /** Providers without a stable file identity get no cache, rather than a length/mtime fallback. */
    fun key(
        file: File,
        modelId: String,
        dimensions: Int,
    ): Key? =
        try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val fileKey = attributes.fileKey()
            if (!attributes.isRegularFile || fileKey == null) {
                null
            } else {
                Key(
                    path = file.canonicalPath,
                    identity =
                        Identity(
                            fileKey = fileKey,
                            length = attributes.size(),
                            lastModified = attributes.lastModifiedTime(),
                            created = attributes.creationTime(),
                        ),
                    modelId = modelId,
                    dimensions = dimensions,
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }

    fun find(
        file: File,
        key: Key,
    ): VectorSnapshot? =
        synchronized(entries) {
            val snapshot = entries[key] ?: return null
            if (key(file, key.modelId, key.dimensions) != key) {
                entries.remove(key)
                return null
            }
            snapshot
        }

    /** Never attach a decoded old mapping to a key observed only after decoding. */
    fun store(
        file: File,
        key: Key,
        snapshot: VectorSnapshot,
    ) {
        synchronized(entries) {
            entries.keys.filter { it.path == key.path }.forEach(entries::remove)
            if (key(file, key.modelId, key.dimensions) == key) {
                entries[key] = snapshot
            }
        }
    }

    fun invalidate(file: File) {
        val path = file.canonicalPath
        synchronized(entries) { entries.keys.filter { it.path == path }.forEach(entries::remove) }
    }

    /** Only entries, not shared locks, may be cleared while stores are alive. */
    fun clear() = synchronized(entries) { entries.clear() }
}
