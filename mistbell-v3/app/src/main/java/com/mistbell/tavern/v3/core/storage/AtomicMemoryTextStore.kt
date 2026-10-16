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

    init {
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName,
        ) { "Memory text file name must be a simple file name." }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
    }

    @Synchronized
    override fun save(records: List<MemoryTextRecord>) {
        val bytes = MemoryTextCodec.encode(records)
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (failure: Throwable) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }

    @Synchronized
    override fun update(
        transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>,
    ): List<MemoryTextRecord> {
        val current = when (val loaded = load()) {
            MemoryTextLoadResult.Missing -> emptyList()
            is MemoryTextLoadResult.Loaded -> loaded.records
            is MemoryTextLoadResult.Corrupt -> throw IllegalStateException(loaded.safeReason)
        }
        val updated = transform(current)
        if (updated != current) save(updated)
        return updated
    }

    @Synchronized
    override fun load(): MemoryTextLoadResult {
        val baseFile = atomicFile.baseFile
        val backup = File(baseFile.path + ".bak")
        if (!baseFile.exists() && !backup.exists()) return MemoryTextLoadResult.Missing
        if (maxOf(baseFile.length(), backup.length()) > MemoryTextCodec.MAX_STORE_BYTES) {
            return MemoryTextLoadResult.Corrupt("Memory text store exceeds the size limit.")
        }
        return try {
            MemoryTextLoadResult.Loaded(
                atomicFile.openRead().use { input -> MemoryTextCodec.decode(input.readBytes()) },
            )
        } catch (_: IOException) {
            MemoryTextLoadResult.Corrupt("Memory text store could not be read.")
        } catch (_: IllegalArgumentException) {
            MemoryTextLoadResult.Corrupt("Memory text store failed integrity or validation checks.")
        }
    }

    /** Retrieval treats missing storage as no memories, but refuses to use corrupt text. */
    override fun loadMemoryTexts(): List<MemoryTextRecord> = when (val result = load()) {
        MemoryTextLoadResult.Missing -> emptyList()
        is MemoryTextLoadResult.Loaded -> result.records
        is MemoryTextLoadResult.Corrupt -> throw IllegalStateException(result.safeReason)
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-memory-texts.bin"
    }
}
