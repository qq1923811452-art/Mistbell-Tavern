package com.mistbell.tavern.v3.feature.memory

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

sealed interface MemoryExtractionJobLoadResult {
    data object Missing : MemoryExtractionJobLoadResult
    data class Loaded(val jobs: List<MemoryExtractionJob>) : MemoryExtractionJobLoadResult
    data class Corrupt(val safeReason: String) : MemoryExtractionJobLoadResult
}

/** Persistence boundary allows repository state-machine tests to run without Android framework stubs. */
interface MemoryExtractionJobPersistence {
    fun save(jobs: List<MemoryExtractionJob>)
    fun load(): MemoryExtractionJobLoadResult
}

/** App-private atomic persistence; corrupt queue data is reported, never silently replaced. */
class MemoryExtractionJobStore private constructor(targetFile: File, @Suppress("UNUSED_PARAMETER") marker: Unit) :
    MemoryExtractionJobPersistence {
    private val file = AtomicFile(targetFile)

    constructor(context: Context, fileName: String = DEFAULT_FILE_NAME) :
        this(File(context.filesDir, validatedFileName(fileName)), Unit)

    /** Directory-based constructor keeps JVM persistence tests independent of Android Context internals. */
    constructor(directory: File) : this(File(directory, DEFAULT_FILE_NAME), Unit)

    @Synchronized
    override fun save(jobs: List<MemoryExtractionJob>) {
        val bytes = MemoryExtractionJobCodec.encode(jobs)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    @Synchronized
    override fun load(): MemoryExtractionJobLoadResult {
        val base = file.baseFile
        val backup = File(base.path + ".bak")
        if (!base.exists() && !backup.exists()) return MemoryExtractionJobLoadResult.Missing
        if (maxOf(base.length(), backup.length()) > MemoryExtractionJobCodec.MAX_BYTES) {
            return MemoryExtractionJobLoadResult.Corrupt("Memory extraction queue exceeds the size limit.")
        }
        return try {
            MemoryExtractionJobLoadResult.Loaded(
                file.openRead().use { MemoryExtractionJobCodec.decode(it.readBytes()) },
            )
        } catch (_: IOException) {
            MemoryExtractionJobLoadResult.Corrupt("Memory extraction queue could not be read.")
        } catch (_: Exception) {
            MemoryExtractionJobLoadResult.Corrupt("Memory extraction queue failed integrity or validation checks.")
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-memory-extraction-queue.bin"

        private fun validatedFileName(fileName: String): String {
            require(
                fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                    '/' !in fileName && '\\' !in fileName,
            ) { "Queue file name must be a simple file name." }
            return fileName
        }
    }
}
