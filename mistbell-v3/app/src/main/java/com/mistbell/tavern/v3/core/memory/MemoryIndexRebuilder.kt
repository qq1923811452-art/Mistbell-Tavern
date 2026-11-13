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
    /** Calls to embedPassages, not native inference calls (which may split or hit a cache). */
    val embeddingRequests: Int,
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
    @Suppress("ReturnCount") // Each early return maps to one distinct, reportable failure stage.
    suspend fun rebuild(
        records: List<MemoryTextRecord>,
        previous: VectorSnapshot? = null,
    ): MemoryIndexRebuildResult {
        val modelId = runtime.modelId
        val dimensions = runtime.dimensions
        metadataFailure(records, modelId, dimensions)?.let { return it }
        sourceTextFailure(records)?.let { return it }
        val timestamp =
            try {
                clockMillis().also { require(it >= 0L) }
            } catch (_: Exception) {
                return MemoryIndexRebuildResult.Failed(
                    MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                    "A valid index timestamp is unavailable.",
                )
            }

        val ordered = records.sortedBy(MemoryTextRecord::id)
        val reusedById = reusedVectors(ordered, previous, modelId, dimensions)
        val pending = ordered.filterNot { reusedById.containsKey(it.id) }
        val embedded =
            when (val outcome = embedPending(pending, dimensions, timestamp)) {
                is EmbeddingOutcome.Ready -> outcome
                EmbeddingOutcome.Failed -> return MemoryIndexRebuildResult.Failed(
                    MemoryIndexRebuildResult.Stage.EMBEDDING,
                    "Local embedding failed or returned an invalid vector; no data was saved.",
                )
            }
        onProgress(
            MemoryIndexRebuildProgress(
                reusedRecords = reusedById.size,
                embeddedRecords = embedded.vectors.size,
                embeddingRequests = embedded.batches,
            ),
        )

        val snapshot =
            assembleSnapshot(modelId, dimensions, ordered, reusedById, embedded.vectors)
                ?: return MemoryIndexRebuildResult.Failed(
                    MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                    "The generated snapshot failed validation.",
                )

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

    private fun metadataFailure(
        records: List<MemoryTextRecord>,
        modelId: String,
        dimensions: Int,
    ): MemoryIndexRebuildResult.Failed? =
        if (modelId.isBlank() ||
            dimensions !in 1..VectorSnapshotCodec.MAX_DIMENSIONS ||
            records.size > MemoryTextCodec.MAX_RECORDS
        ) {
            MemoryIndexRebuildResult.Failed(
                MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                "Model metadata or source record set is invalid.",
            )
        } else {
            null
        }

    @Suppress("ReturnCount") // Guard clauses report which limit was crossed.
    private fun sourceTextFailure(records: List<MemoryTextRecord>): MemoryIndexRebuildResult.Failed? {
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
        return null
    }

    private sealed interface EmbeddingOutcome {
        data class Ready(val vectors: Map<String, StoredVector>, val batches: Int) : EmbeddingOutcome

        data object Failed : EmbeddingOutcome
    }

    /** Embeds every record that could not be reused, in configured batches. */
    private suspend fun embedPending(
        pending: List<MemoryTextRecord>,
        dimensions: Int,
        timestamp: Long,
    ): EmbeddingOutcome {
        val batchSize = runtime.passageBatchSize.coerceAtLeast(1)
        val embedded = LinkedHashMap<String, StoredVector>(pending.size)
        var batches = 0
        try {
            for (chunk in pending.chunked(batchSize)) {
                val vectors = runtime.embedPassages(chunk.map { it.text })
                batches++
                require(vectors.size == chunk.size) {
                    "Embedding runtime returned a mismatched batch size."
                }
                chunk.forEachIndexed { index, record ->
                    embedded[record.id] = storedVector(record, vectors[index], dimensions, timestamp)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EmbeddingOutcome.Failed
        }
        return EmbeddingOutcome.Ready(embedded, batches)
    }

    private fun storedVector(
        record: MemoryTextRecord,
        vector: List<Float>,
        dimensions: Int,
        timestamp: Long,
    ): StoredVector {
        require(vector.size == dimensions) { "Embedding dimension does not match the loaded model." }
        require(vector.isNotEmpty() && vector.all(Float::isFinite)) {
            "Embedding output contains invalid values."
        }
        require(vector.any { it != 0f }) { "Embedding output is a zero vector." }
        return StoredVector(
            id = record.id,
            sourceTextSha256 = record.sourceTextSha256,
            createdAtEpochMillis = timestamp,
            values = vector.toFloatArray().asList(),
        )
    }

    private fun assembleSnapshot(
        modelId: String,
        dimensions: Int,
        ordered: List<MemoryTextRecord>,
        reusedById: Map<String, StoredVector>,
        embeddedById: Map<String, StoredVector>,
    ): VectorSnapshot? {
        val records =
            ordered.map { record ->
                reusedById[record.id] ?: embeddedById.getValue(record.id)
            }
        return try {
            VectorSnapshot(modelId = modelId, dimensions = dimensions, records = records)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Copies reusable vectors out of the previous snapshot. Model and dimension must match, and
     * each record must keep its ID and source-text hash; anything else is re-embedded.
     *
     * The source-text hash stands in for the embedded input only because the memory runtime
     * embeds `formatDocument(text)` with no title. Changing that formatting policy (or swapping the
     * model file behind the same model ID) makes old vectors stale while this key still matches;
     * such a change must bump the model identity or the whole index is silently reused.
     */
    private fun reusedVectors(
        ordered: List<MemoryTextRecord>,
        previous: VectorSnapshot?,
        modelId: String,
        dimensions: Int,
    ): Map<String, StoredVector> {
        val usable =
            previous?.takeIf { it.modelId == modelId && it.dimensions == dimensions }
                ?: return emptyMap()
        val byId = usable.records.associateBy(StoredVector::id)
        return ordered.mapNotNull { record ->
            val candidate =
                byId[record.id]
                    ?.takeIf { it.sourceTextSha256 == record.sourceTextSha256 }
                    ?: return@mapNotNull null
            // A damaged reuse candidate must never abort the rebuild; re-embed instead.
            runCatching {
                StoredVector(
                    id = record.id,
                    sourceTextSha256 = record.sourceTextSha256,
                    createdAtEpochMillis = candidate.createdAtEpochMillis,
                    values = copyValues(candidate.values, dimensions),
                )
            }.getOrNull()
        }.associateBy(StoredVector::id)
    }

    private fun copyValues(
        values: List<Float>,
        dimensions: Int,
    ): List<Float> {
        require(values.size == dimensions) { "Stored vector dimension does not match the model." }
        val copy = FloatArray(dimensions)
        if (values is FloatVectorView) {
            values.fillInto(copy)
        } else {
            for (position in copy.indices) copy[position] = values[position]
        }
        require(copy.all(Float::isFinite)) { "Stored vector contains invalid values." }
        require(copy.any { it != 0f }) { "Stored vector is a zero vector." }
        return copy.asList()
    }
}
