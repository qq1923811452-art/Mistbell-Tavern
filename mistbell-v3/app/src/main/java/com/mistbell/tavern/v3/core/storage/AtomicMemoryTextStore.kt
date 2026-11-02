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
        synchronized(pathLock) { saveLocked(records) }
    }

    /**
     * Read-modify-write under the shared per-path lock, so two store instances cannot interleave
     * a load and a save and silently drop each other's records.
     */
    override fun update(
        transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>,
    ): List<MemoryTextRecord> = synchronized(pathLock) {
        val current = when (val loaded = loadLocked()) {
            MemoryTextLoadResult.Missing -> emptyList()
            is MemoryTextLoadResult.Loaded -> loaded.records
            is MemoryTextLoadResult.Corrupt -> throw IllegalStateException(loaded.safeReason)
        }
        val updated = transform(current)
        if (updated != current) saveLocked(updated)
        updated
    }

    override fun load(): MemoryTextLoadResult = synchronized(pathLock) { loadLocked() }

    /** Retrieval treats missing storage as no memories, but refuses to use corrupt text. */
    override fun loadMemoryTexts(): List<MemoryTextRecord> = when (val result = load()) {
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
        val identity = if (baseFile.exists()) baseFile else backup
        if (!identity.exists()) return MemoryTextLoadResult.Missing
        if (maxOf(baseFile.length(), backup.length()) > MemoryTextCodec.MAX_STORE_BYTES) {
            return MemoryTextLoadResult.Corrupt("Memory text store exceeds the size limit.")
        }
        val key = MemoryTextMemoryCache.key(baseFile, identity)
        if (key != null) {
            MemoryTextMemoryCache.find(key)?.let { return MemoryTextLoadResult.Loaded(it) }
        }
        return try {
            val decoded = atomicFile.openRead().use { input -> MemoryTextCodec.decode(input.readBytes()) }
            val records = if (key != null) MemoryTextMemoryCache.store(key, decoded) else decoded
            MemoryTextLoadResult.Loaded(records)
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
