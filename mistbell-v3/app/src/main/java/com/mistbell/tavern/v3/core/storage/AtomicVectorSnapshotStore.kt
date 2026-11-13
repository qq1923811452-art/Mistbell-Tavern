package com.mistbell.tavern.v3.core.storage

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import com.mistbell.tavern.v3.core.memory.VectorSnapshotCodec
import java.io.File
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.channels.FileChannel

fun interface VectorSnapshotLoader {
    fun load(
        expectedModelId: String,
        expectedDimensions: Int,
    ): VectorSnapshotLoadResult
}

fun interface VectorSnapshotSaver {
    fun save(snapshot: VectorSnapshot)
}

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
) : VectorSnapshotLoader, VectorSnapshotSaver {
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

    override fun save(snapshot: VectorSnapshot): Unit =
        MemoryScopeStorageLock.withLock(atomicFile.baseFile) {
            val bytes = VectorSnapshotCodec.encode(snapshot)
            try {
                val stream = atomicFile.startWrite()
                try {
                    stream.write(bytes)
                    atomicFile.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomicFile.failWrite(stream)
                    throw failure
                }
            } finally {
                // Also discard on failure: AtomicFile may have restored/replaced the base file.
                VectorSnapshotMemoryCache.invalidate(atomicFile.baseFile)
            }
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Recovery and validation use explicit exits.
    override fun load(
        expectedModelId: String,
        expectedDimensions: Int,
    ): VectorSnapshotLoadResult {
        return MemoryScopeStorageLock.withLock(atomicFile.baseFile) {
            require(expectedModelId.isNotBlank())
            require(expectedDimensions > 0)
            val file = atomicFile.baseFile
            val backup = File(file.path + ".bak")
            if (!file.exists() && !backup.exists()) return VectorSnapshotLoadResult.Missing
            if (maxOf(file.length(), backup.length()) > VectorSnapshotCodec.MAX_SNAPSHOT_BYTES) {
                return VectorSnapshotLoadResult.Corrupt("Vector snapshot exceeds the size limit.")
            }

            // Observe before openRead as well: it may restore .bak or the path may be replaced
            // between opening the descriptor and taking the next identity. Such reads are not cached.
            val beforeOpen = VectorSnapshotMemoryCache.key(file, expectedModelId, expectedDimensions)
            var decodedKey: VectorSnapshotMemoryCache.Key? = null
            val snapshot =
                try {
                    atomicFile.openRead().use { input ->
                        // Always let AtomicFile perform recovery before considering a cache hit.
                        val openedKey = VectorSnapshotMemoryCache.key(file, expectedModelId, expectedDimensions)
                        val stableKey = openedKey?.takeIf { it == beforeOpen }
                        val channel = input.channel
                        val size = channel.size()
                        require(size in 1..VectorSnapshotCodec.MAX_SNAPSHOT_BYTES.toLong()) {
                            "Vector snapshot size is invalid."
                        }
                        if (stableKey != null && stableKey.identity.length == size) {
                            val cached = VectorSnapshotMemoryCache.find(file, stableKey)
                            if (cached != null) return completeLoad(cached, expectedModelId, expectedDimensions)
                            decodedKey = stableKey
                        }
                        val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size)
                        VectorSnapshotCodec.decodeMapped(mapped)
                    }
                } catch (failure: IOException) {
                    return VectorSnapshotLoadResult.Corrupt("Vector snapshot could not be read.")
                } catch (failure: BufferUnderflowException) {
                    return VectorSnapshotLoadResult.Corrupt("Vector snapshot is truncated.")
                } catch (failure: IllegalArgumentException) {
                    return VectorSnapshotLoadResult.Corrupt(
                        failure.message ?: "Vector snapshot failed validation.",
                    )
                }

            if (snapshot.modelId != expectedModelId || snapshot.dimensions != expectedDimensions) {
                return completeLoad(snapshot, expectedModelId, expectedDimensions)
            }
            decodedKey?.let { VectorSnapshotMemoryCache.store(file, it, snapshot) }
            return VectorSnapshotLoadResult.Loaded(snapshot)
        }
    }

    private fun completeLoad(
        snapshot: VectorSnapshot,
        expectedModelId: String,
        expectedDimensions: Int,
    ): VectorSnapshotLoadResult =
        when {
            snapshot.modelId != expectedModelId ->
                VectorSnapshotLoadResult.Incompatible("Embedding model ID does not match.")
            snapshot.dimensions != expectedDimensions ->
                VectorSnapshotLoadResult.Incompatible("Embedding dimension does not match.")
            else -> VectorSnapshotLoadResult.Loaded(snapshot)
        }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-vectors.bin"
    }
}
