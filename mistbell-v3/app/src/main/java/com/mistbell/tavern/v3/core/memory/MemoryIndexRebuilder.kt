package com.mistbell.tavern.v3.core.memory

import kotlinx.coroutines.CancellationException

/**
 * The configured local embedding runtime. Metadata must describe the model actually loaded,
 * not a desired model or a hard-coded guess.
 */
interface PassageEmbeddingRuntime : IdentifiedQueryEmbeddingProvider {
    /** Uses document/passage formatting, which can differ from query formatting for retrieval models. */
    suspend fun embedPassage(text: String): List<Float> = embed(text)

    /**
     * Largest number of passages a single inference call accepts. 1 keeps the original
     * one-passage-at-a-time contract; runtimes that expose a real batch API raise it.
     */
    val passageBatchSize: Int get() = 1

    /**
     * Embeds passages in order. The default preserves the single-passage contract, so a runtime
     * that cannot batch stays correct without implementing this.
     */
    suspend fun embedPassages(texts: List<String>): List<List<Float>> = texts.map { embedPassage(it) }
}

/** Counts reported once per rebuild so diagnostics can show how much work was avoided. */
data class MemoryIndexRebuildProgress(
    val reusedRecords: Int,
    val embeddedRecords: Int,
    val inferenceBatches: Int,
)

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
 * Reuse: when the caller passes the previously persisted snapshot, a record keeps its vector only
 * if the model ID, the dimension, the record ID, and the source-text hash all still match. Anything
 * else is re-embedded, so a library where 20 of 1000 passages changed costs 20 embeddings instead
 * of 1000. Reused values are copied out of the old snapshot: mapped views must not stay bound to
 * the previous file's buffer, and the encoder needs addressable values anyway.
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
    private val onProgress: (MemoryIndexRebuildProgress) -> Unit = {},
) {
    suspend fun rebuild(
        records: List<MemoryTextRecord>,
        previous: VectorSnapshot? = null,
    ): MemoryIndexRebuildResult {
        val modelId = runtime.modelId
        val dimensions = runtime.dimensions
        if (modelId.isBlank() ||
            dimensions !in 1..VectorSnapshotCodec.MAX_DIMENSIONS ||
            records.size > MemoryTextCodec.MAX_RECORDS
        ) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "Model metadata or source record set is invalid.",
            )
        }
        val seenIds = HashSet<String>(records.size)
        var sourceTextBytes = 0L
        for (record in records) {
            if (!seenIds.add(record.id)) {
                return MemoryIndexRebuildResult.Failed(
                    MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                    "Model metadata or source record set is invalid.",
                )
            }
            sourceTextBytes += record.text.toByteArray(Charsets.UTF_8).size.toLong()
            if (sourceTextBytes > MemoryTextCodec.MAX_STORE_BYTES) {
                return MemoryIndexRebuildResult.Failed(
                    MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                    "Source text exceeds the persistence size limit.",
                )
            }
        }
        val timestamp = try {
            clockMillis().also { require(it >= 0L) }
        } catch (_: Exception) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "A valid index timestamp is unavailable.",
            )
        }

        val ordered = records.sortedBy(MemoryTextRecord::id)
        val previousById = reusableVectors(previous, modelId, dimensions)
        val reusedById = LinkedHashMap<String, StoredVector>(ordered.size)
        val pending = ArrayList<MemoryTextRecord>()
        for (record in ordered) {
            val candidate = previousById[record.id]
            // A damaged reuse candidate must never abort the rebuild; fall back to re-embedding.
            val reused = candidate
                ?.takeIf { it.sourceTextSha256 == record.sourceTextSha256 }
                ?.let { previousVector ->
                    runCatching {
                        StoredVector(
                            id = record.id,
                            sourceTextSha256 = record.sourceTextSha256,
                            createdAtEpochMillis = previousVector.createdAtEpochMillis,
                            values = copyValues(previousVector.values, dimensions),
                        )
                    }.getOrNull()
                }
            if (reused != null) {
                reusedById[record.id] = reused
            } else {
                pending += record
            }
        }

        val batchSize = runtime.passageBatchSize.coerceAtLeast(1)
        val embeddedById = LinkedHashMap<String, StoredVector>(pending.size)
        var batches = 0
        try {
            for (chunk in pending.chunked(batchSize)) {
                val vectors = runtime.embedPassages(chunk.map { it.text })
                batches++
                require(vectors.size == chunk.size) {
                    "Embedding runtime returned a mismatched batch size."
                }
                chunk.forEachIndexed { index, record ->
                    val vector = vectors[index]
                    require(vector.size == dimensions) {
                        "Embedding dimension does not match the loaded model."
                    }
                    require(vector.isNotEmpty() && vector.all(Float::isFinite)) {
                        "Embedding output contains invalid values."
                    }
                    require(vector.any { it != 0f }) {
                        "Embedding output is a zero vector."
                    }
                    embeddedById[record.id] = StoredVector(
                        id = record.id,
                        sourceTextSha256 = record.sourceTextSha256,
                        createdAtEpochMillis = timestamp,
                        values = vector.toFloatArray().asList(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.EMBEDDING,
                "Local embedding failed or returned an invalid vector; no data was saved.",
            )
        }

        val snapshotRecords = ordered.map { record ->
            reusedById[record.id] ?: embeddedById.getValue(record.id)
        }
        onProgress(
            MemoryIndexRebuildProgress(
                reusedRecords = reusedById.size,
                embeddedRecords = embeddedById.size,
                inferenceBatches = batches,
            ),
        )

        val snapshot = try {
            VectorSnapshot(modelId = modelId, dimensions = dimensions, records = snapshotRecords)
        } catch (_: IllegalArgumentException) {
            return MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "The generated snapshot failed validation.",
            )
        }

        try {
            saveSourceTexts(ordered)
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

    private fun reusableVectors(
        previous: VectorSnapshot?,
        modelId: String,
        dimensions: Int,
    ): Map<String, StoredVector> {
        if (previous == null) return emptyMap()
        // A different model or vector size invalidates every stored vector, even for identical text.
        if (previous.modelId != modelId || previous.dimensions != dimensions) return emptyMap()
        return previous.records.associateBy(StoredVector::id)
    }

    private fun copyValues(values: List<Float>, dimensions: Int): List<Float> {
        require(values.size == dimensions) { "Stored vector dimension does not match the model." }
        val copy = FloatArray(dimensions)
        if (values is FloatVectorView) {
            values.fillInto(copy)
        } else {
            for (position in copy.indices) copy[position] = values[position]
        }
        require(copy.all(Float::isFinite)) { "Stored vector contains invalid values." }
        return copy.asList()
    }
}
