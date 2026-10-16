package com.mistbell.tavern.v3.core.storage

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.memory.MemoryJobLedger
import com.mistbell.tavern.v3.core.memory.MemoryJobLedgerCodec
import java.io.File
import java.io.IOException

sealed interface MemoryJobLedgerLoadResult {
    data object Missing : MemoryJobLedgerLoadResult
    data class Loaded(val ledger: MemoryJobLedger) : MemoryJobLedgerLoadResult
    data class Corrupt(val safeReason: String) : MemoryJobLedgerLoadResult
}

interface MemoryJobLedgerPersistence {
    fun save(ledger: MemoryJobLedger)
    fun load(): MemoryJobLedgerLoadResult
}

/** App-private atomic persistence. Corrupt state is reported, never silently overwritten. */
class AtomicMemoryJobLedgerStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : MemoryJobLedgerPersistence {
    private val atomicFile: AtomicFile

    init {
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName,
        ) { "Memory ledger file name must be a simple file name." }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
    }

    @Synchronized
    override fun save(ledger: MemoryJobLedger) {
        val bytes = MemoryJobLedgerCodec.encode(ledger)
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
    override fun load(): MemoryJobLedgerLoadResult {
        val baseFile = atomicFile.baseFile
        val backup = File(baseFile.path + ".bak")
        if (!baseFile.exists() && !backup.exists()) return MemoryJobLedgerLoadResult.Missing
        if (maxOf(baseFile.length(), backup.length()) > MemoryJobLedgerCodec.MAX_LEDGER_BYTES) {
            return MemoryJobLedgerLoadResult.Corrupt("Memory job ledger exceeds the size limit.")
        }
        return try {
            val ledger = atomicFile.openRead().use { input ->
                MemoryJobLedgerCodec.decode(input.readBytes())
            }
            MemoryJobLedgerLoadResult.Loaded(ledger)
        } catch (_: IOException) {
            MemoryJobLedgerLoadResult.Corrupt("Memory job ledger could not be read.")
        } catch (_: IllegalArgumentException) {
            MemoryJobLedgerLoadResult.Corrupt("Memory job ledger failed integrity or validation checks.")
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-memory-jobs.bin"
    }
}
