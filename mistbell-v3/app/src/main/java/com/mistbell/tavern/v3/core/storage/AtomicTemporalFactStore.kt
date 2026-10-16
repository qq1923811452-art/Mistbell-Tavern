package com.mistbell.tavern.v3.core.storage

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.memory.TemporalFactCodec
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import java.io.File
import java.io.IOException

sealed interface TemporalFactLoadResult {
    data object Missing : TemporalFactLoadResult
    data class Loaded(val facts: List<TemporalMemoryFact>) : TemporalFactLoadResult
    data class Corrupt(val safeReason: String) : TemporalFactLoadResult
}

/** App-private atomic storage. Corrupt originals are reported, never silently replaced. */
class AtomicTemporalFactStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) {
    private val atomicFile: AtomicFile

    init {
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName,
        ) { "Temporal fact file name must be a simple file name." }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
    }

    @Synchronized
    fun save(facts: List<TemporalMemoryFact>) {
        val bytes = TemporalFactCodec.encode(facts)
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
    fun load(): TemporalFactLoadResult {
        val baseFile = atomicFile.baseFile
        val backup = File(baseFile.path + ".bak")
        if (!baseFile.exists() && !backup.exists()) return TemporalFactLoadResult.Missing
        if (maxOf(baseFile.length(), backup.length()) > TemporalFactCodec.MAX_STORE_BYTES) {
            return TemporalFactLoadResult.Corrupt("Temporal fact store exceeds the size limit.")
        }
        return try {
            TemporalFactLoadResult.Loaded(
                atomicFile.openRead().use { input -> TemporalFactCodec.decode(input.readBytes()) },
            )
        } catch (_: IOException) {
            TemporalFactLoadResult.Corrupt("Temporal fact store could not be read.")
        } catch (_: IllegalArgumentException) {
            TemporalFactLoadResult.Corrupt("Temporal fact store failed integrity or validation checks.")
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-temporal-facts.bin"
    }
}
