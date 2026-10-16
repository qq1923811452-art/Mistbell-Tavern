package com.mistbell.tavern.v3.core.storage

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.memory.MemoryFact
import com.mistbell.tavern.v3.core.memory.MemoryFactHistoryCodec
import java.io.File
import java.io.IOException

sealed interface MemoryFactHistoryLoadResult {
    data object Missing : MemoryFactHistoryLoadResult
    data class Loaded(val history: List<MemoryFact>) : MemoryFactHistoryLoadResult
    data class Corrupt(val safeReason: String) : MemoryFactHistoryLoadResult
}

/** App-private atomic fact-history storage. Invalid data is never silently replaced. */
class AtomicMemoryFactHistoryStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) {
    private val atomicFile: AtomicFile

    init {
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName,
        ) { "Fact history file name must be a simple file name." }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
    }

    @Synchronized
    fun save(history: List<MemoryFact>) {
        val bytes = MemoryFactHistoryCodec.encode(history)
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
    fun load(): MemoryFactHistoryLoadResult {
        val baseFile = atomicFile.baseFile
        val backup = File(baseFile.path + ".bak")
        if (!baseFile.exists() && !backup.exists()) return MemoryFactHistoryLoadResult.Missing
        if (maxOf(baseFile.length(), backup.length()) > MemoryFactHistoryCodec.MAX_HISTORY_BYTES) {
            return MemoryFactHistoryLoadResult.Corrupt("Fact history exceeds the size limit.")
        }
        return try {
            val history = atomicFile.openRead().use { input ->
                MemoryFactHistoryCodec.decode(input.readBytes())
            }
            MemoryFactHistoryLoadResult.Loaded(history)
        } catch (_: IOException) {
            MemoryFactHistoryLoadResult.Corrupt("Fact history could not be read.")
        } catch (_: IllegalArgumentException) {
            MemoryFactHistoryLoadResult.Corrupt("Fact history failed integrity or validation checks.")
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-memory-facts.bin"
    }
}
