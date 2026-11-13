package com.mistbell.tavern.v3.core.storage

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.memory.MemoryTextCodec
import com.mistbell.tavern.v3.core.memory.MemoryTextProvider
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import java.io.File
import java.io.IOException

sealed interface MemoryTextLoadResult {
    data object Missing : MemoryTextLoadResult

    data class Loaded(val records: List<MemoryTextRecord>) : MemoryTextLoadResult

    data class Corrupt(val safeReason: String) : MemoryTextLoadResult
}

interface MemoryTextPersistence : MemoryTextProvider {
    fun save(records: List<MemoryTextRecord>)

    fun load(): MemoryTextLoadResult

    fun update(transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>): List<MemoryTextRecord>
}

/** App-private atomic storage for source text referenced by vector content hashes. */
class AtomicMemoryTextStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : MemoryTextPersistence {
    private val atomicFile: AtomicFile

    /** Shared with every other store instance for the same path, not just this object. */
    private val pathLock: Any

    init {
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName,
        ) { "Memory text file name must be a simple file name." }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
        pathLock = MemoryTextMemoryCache.lockFor(atomicFile.baseFile)
    }

    override fun save(records: List<MemoryTextRecord>) {
        MemoryScopeStorageLock.withLock(atomicFile.baseFile) { synchronized(pathLock) { saveLocked(records) } }
    }

    /**
     * Read-modify-write under the shared per-path lock, so two store instances cannot interleave
     * a load and a save and silently drop each other's records.
     */
    override fun update(transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>): List<MemoryTextRecord> =
        MemoryScopeStorageLock.withLock(atomicFile.baseFile) {
            synchronized(pathLock) {
                val current =
                    when (val loaded = loadLocked()) {
                        MemoryTextLoadResult.Missing -> emptyList()
                        is MemoryTextLoadResult.Loaded -> loaded.records
                        is MemoryTextLoadResult.Corrupt -> throw IllegalStateException(loaded.safeReason)
                    }
                val updated = transform(current)
                if (updated != current) saveLocked(updated)
                updated
            }
        }

    override fun load(): MemoryTextLoadResult =
        MemoryScopeStorageLock.withLock(atomicFile.baseFile) {
            synchronized(pathLock) { loadLocked() }
        }

    /** Holds the text-store lock until an index commit finishes, excluding intervening updates. */
    fun commitIndexIfCurrent(
        expected: List<MemoryTextRecord>,
        commit: () -> Unit,
    ) {
        // The vector store also takes the storage transaction lock: acquire it before pathLock,
        // matching load/update, so an index commit cannot deadlock with a concurrent writer.
        MemoryScopeStorageLock.withLock(atomicFile.baseFile) {
            synchronized(pathLock) {
                val current =
                    when (val loaded = loadLocked()) {
                        MemoryTextLoadResult.Missing -> emptyList()
                        is MemoryTextLoadResult.Loaded -> loaded.records
                        is MemoryTextLoadResult.Corrupt -> error(loaded.safeReason)
                    }
                check(current.sortedBy { it.id } == expected.sortedBy { it.id }) {
                    "Source text changed during index rebuild."
                }
                commit()
            }
        }
    }

    /** Retrieval treats missing storage as no memories, but refuses to use corrupt text. */
    override fun loadMemoryTexts(): List<MemoryTextRecord> =
        when (val result = load()) {
            MemoryTextLoadResult.Missing -> emptyList()
            is MemoryTextLoadResult.Loaded -> result.records
            is MemoryTextLoadResult.Corrupt -> throw IllegalStateException(result.safeReason)
        }

    private fun saveLocked(records: List<MemoryTextRecord>) {
        try {
            val bytes = MemoryTextCodec.encode(records)
            val stream = atomicFile.startWrite()
            try {
                stream.write(bytes)
                atomicFile.finishWrite(stream)
            } catch (failure: Throwable) {
                atomicFile.failWrite(stream)
                throw failure
            }
        } finally {
            // Bump even on failure: the on-disk state may be half-written, so the next read must
            // go back to disk instead of trusting a previous decode.
            MemoryTextMemoryCache.invalidate(atomicFile.baseFile)
        }
    }

    @Suppress("ReturnCount") // Guard clauses distinguish missing, oversized, cached and corrupt.
    private fun loadLocked(): MemoryTextLoadResult {
        val baseFile = atomicFile.baseFile
        val backup = File(baseFile.path + ".bak")
        if (!baseFile.exists() && !backup.exists()) return MemoryTextLoadResult.Missing
        if (maxOf(baseFile.length(), backup.length()) > MemoryTextCodec.MAX_STORE_BYTES) {
            return MemoryTextLoadResult.Corrupt("Memory text store exceeds the size limit.")
        }
        val beforeOpen = MemoryTextMemoryCache.key(baseFile)
        return try {
            atomicFile.openRead().use { input ->
                // AtomicFile must recover .bak even when a decoded entry is already cached.
                val openedKey = MemoryTextMemoryCache.key(baseFile)
                val stableKey = openedKey?.takeIf { it == beforeOpen && it.length == input.channel.size() }
                val cached = stableKey?.let { MemoryTextMemoryCache.find(baseFile, it) }
                if (cached != null) return MemoryTextLoadResult.Loaded(cached)
                val decoded = MemoryTextCodec.decode(input.readBytes())
                MemoryTextLoadResult.Loaded(MemoryTextMemoryCache.store(baseFile, stableKey, decoded))
            }
        } catch (_: IOException) {
            MemoryTextLoadResult.Corrupt("Memory text store could not be read.")
        } catch (_: IllegalArgumentException) {
            MemoryTextLoadResult.Corrupt("Memory text store failed integrity or validation checks.")
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-memory-texts.bin"
    }
}
