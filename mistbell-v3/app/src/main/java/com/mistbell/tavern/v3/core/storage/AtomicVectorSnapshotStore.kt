package com.mistbell.tavern.v3.core.storage

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import com.mistbell.tavern.v3.core.memory.VectorSnapshotCodec
import java.io.File
import java.io.IOException

sealed interface VectorSnapshotLoadResult {
    data object Missing : VectorSnapshotLoadResult
    data class Loaded(val snapshot: VectorSnapshot) : VectorSnapshotLoadResult
    data class Incompatible(val reason: String) : VectorSnapshotLoadResult
    data class Corrupt(val reason: String) : VectorSnapshotLoadResult
}

/**
 * App-private, atomic persistence for the local vector snapshot. A failed read never deletes
 * or replaces the existing file; callers may offer an explicit rebuild/quarantine workflow.
 */
class AtomicVectorSnapshotStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) {
    private val atomicFile: AtomicFile

    init {
        require(
            fileName.isNotBlank() &&
                fileName != "." &&
                fileName != ".." &&
                '/' !in fileName &&
                '\\' !in fileName,
        ) {
            "Vector snapshot file name must be a simple file name."
        }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
    }

    @Synchronized
    fun save(snapshot: VectorSnapshot) {
        val bytes = VectorSnapshotCodec.encode(snapshot)
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
    fun load(expectedModelId: String, expectedDimensions: Int): VectorSnapshotLoadResult {
        require(expectedModelId.isNotBlank())
        require(expectedDimensions > 0)
        val file = atomicFile.baseFile
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) return VectorSnapshotLoadResult.Missing
        if (maxOf(file.length(), backup.length()) > VectorSnapshotCodec.MAX_SNAPSHOT_BYTES) {
            return VectorSnapshotLoadResult.Corrupt("Vector snapshot exceeds the size limit.")
        }

        val snapshot = try {
            atomicFile.openRead().use { input ->
                VectorSnapshotCodec.decode(input.readBytes())
            }
        } catch (failure: IOException) {
            return VectorSnapshotLoadResult.Corrupt("Vector snapshot could not be read.")
        } catch (failure: IllegalArgumentException) {
            return VectorSnapshotLoadResult.Corrupt(
                failure.message ?: "Vector snapshot failed validation.",
            )
        }

        if (snapshot.modelId != expectedModelId) {
            return VectorSnapshotLoadResult.Incompatible("Embedding model ID does not match.")
        }
        if (snapshot.dimensions != expectedDimensions) {
            return VectorSnapshotLoadResult.Incompatible("Embedding dimension does not match.")
        }
        return VectorSnapshotLoadResult.Loaded(snapshot)
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-vectors.bin"
    }
}
