package com.mistbell.tavern.v3.core.memory

import kotlinx.coroutines.CancellationException

/**
 * The configured local embedding runtime. Metadata must describe the model actually loaded,
 * not a desired model or a hard-coded guess.
 */
interface PassageEmbeddingRuntime : QueryEmbeddingProvider {
    val modelId: String
    val dimensions: Int
}

/** Result of rebuilding the durable source-text/vector pair. */
sealed interface MemoryIndexRebuildResult {
    data class Success(val indexedRecords: Int, val modelId: String, val dimensions: Int) :
        MemoryIndexRebuildResult

    data class Failed(val stage: Stage, val reason: String) : MemoryIndexRebuildResult

    enum class Stage {
        INVALID_INPUT,
        EMBEDDING,
        SOURCE_TEXT_SAVE,
        SNAPSHOT_SAVE,
    }
}

/**
 * Builds a complete replacement snapshot before touching persistence.
 *
 * Source text is written before the vector snapshot. If the final snapshot write fails, retrieval's
 * sourceTextSha256 join prevents vectors for changed/removed text from being used. Unchanged
 * passages may continue to use their old vectors, and the rebuild can safely be retried.
 */
class MemoryIndexRebuilder(
    private val runtime: PassageEmbeddingRuntime,
    private val saveSourceTexts: (List<MemoryTextRecord>) -> Unit,
    private val saveSnapshot: (VectorSnapshot) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun rebuild(records: List<MemoryTextRecord>): MemoryIndexRebuildResult {
        val modelId = runtime.modelId
        val dimensions = runtime.dimensions
        if (modelId.isBlank() ||
            dimensions !in 1..VectorSnapshotCodec.MAX_DIMENSIONS ||
            records.size > MemoryTextCodec.MAX_RECORDS ||
            records.map(MemoryTextRecord::id).distinct().size != records.size
        ) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "Model metadata or source record set is invalid.",
            )
        }
        if (records.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } >
            MemoryTextCodec.MAX_STORE_BYTES
        ) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "Source text exceeds the persistence size limit.",
            )
        }
        if (records.size.toLong() * dimensions > VectorSnapshotCodec.MAX_VECTOR_VALUES) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "The rebuilt index would exceed the vector value limit.",
            )
        }

        val timestamp = try {
            clockMillis().also { require(it >= 0L) }
        } catch (_: Exception) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "A valid index timestamp is unavailable.",
            )
        }

        val snapshotRecords = try {
            records.sortedBy(MemoryTextRecord::id).map { record ->
                val vector = runtime.embed(record.text)
                require(vector.size == dimensions) {
                    "Embedding dimension does not match the loaded model."
                }
                require(vector.isNotEmpty() && vector.all(Float::isFinite)) {
                    "Embedding output contains invalid values."
                }
                require(vector.any { it != 0f }) {
                    "Embedding output is a zero vector."
                }
                StoredVector(
                    id = record.id,
                    sourceTextSha256 = record.sourceTextSha256,
                    createdAtEpochMillis = timestamp,
                    values = vector.toList(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.EMBEDDING,
                "Local embedding failed or returned an invalid vector; no data was saved.",
            )
        }

        val snapshot = try {
            VectorSnapshot(modelId = modelId, dimensions = dimensions, records = snapshotRecords)
        } catch (_: IllegalArgumentException) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "The generated snapshot failed validation.",
            )
        }

        try {
            saveSourceTexts(records.sortedBy(MemoryTextRecord::id))
        } catch (_: Exception) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.SOURCE_TEXT_SAVE,
                "Source text could not be saved; vector snapshot was not changed.",
            )
        }

        try {
            saveSnapshot(snapshot)
        } catch (_: Exception) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.SNAPSHOT_SAVE,
                "Vector snapshot could not be saved. Source hashes prevent stale vectors from matching changed text; retry the rebuild.",
            )
        }

        return MemoryIndexRebuildResult.Success(
            indexedRecords = snapshot.records.size,
            modelId = snapshot.modelId,
            dimensions = snapshot.dimensions,
        )
    }
}
