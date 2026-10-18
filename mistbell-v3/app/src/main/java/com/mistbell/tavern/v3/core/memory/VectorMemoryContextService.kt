package com.mistbell.tavern.v3.core.memory

import kotlinx.coroutines.CancellationException

/** Storage adapter result for loading a validated vector snapshot. */
sealed interface VectorSnapshotReadResult {
    data object Missing : VectorSnapshotReadResult
    data class Loaded(val snapshot: VectorSnapshot) : VectorSnapshotReadResult
    data class Incompatible(val reason: String) : VectorSnapshotReadResult
    data class Corrupt(val reason: String) : VectorSnapshotReadResult
}

/** The local embedding runtime implementation must report failures instead of returning fake vectors. */
fun interface QueryEmbeddingProvider {
    suspend fun embed(text: String): List<Float>
}

/** Optional metadata contract for runtimes that can identify the loaded model and vector size. */
interface IdentifiedQueryEmbeddingProvider : QueryEmbeddingProvider {
    val modelId: String
    val dimensions: Int
}

/** Supplies the current source passages corresponding to stored vector content hashes. */
fun interface MemoryTextProvider {
    fun loadMemoryTexts(): List<MemoryTextRecord>
}

/** Loads a validated snapshot, allowing storage to report missing, corrupt, or incompatible data. */
fun interface VectorSnapshotProvider {
    fun loadSnapshot(): VectorSnapshotReadResult
}

sealed interface VectorMemoryContextResult {
    data class Ready(val memories: List<RetrievedMemory>) : VectorMemoryContextResult
    data class Unavailable(val reason: Reason) : VectorMemoryContextResult
    data object NoRelevantMemories : VectorMemoryContextResult

    enum class Reason {
        SNAPSHOT_MISSING,
        SNAPSHOT_CORRUPT,
        SNAPSHOT_INCOMPATIBLE,
        EMBEDDING_FAILED,
        SOURCE_TEXT_UNAVAILABLE,
        INVALID_QUERY_VECTOR,
        UNEXPECTED_FAILURE,
    }
}

/**
 * Coordinates query embedding, snapshot loading, semantic retrieval, and source-text validation.
 *
 * The class deliberately does not own a model/runtime implementation. Production wiring must
 * inject the configured local embedding runtime and expose its actual backend status separately.
 * No remote API fallback is attempted here.
 */
class VectorMemoryContextService(
    private val snapshots: VectorSnapshotProvider,
    private val embeddings: QueryEmbeddingProvider,
    private val textProvider: MemoryTextProvider,
    private val resultLimit: Int = VectorSimilaritySearch.DEFAULT_RESULT_LIMIT,
    private val minimumScore: Double = VectorSimilaritySearch.DEFAULT_MINIMUM_SCORE,
    private val maxCharacters: Int = VectorMemoryRetriever.DEFAULT_MAX_CHARACTERS,
) {
    init {
        require(resultLimit in 1..VectorSimilaritySearch.MAX_RESULT_LIMIT)
        require(minimumScore.isFinite() && minimumScore in -1.0..1.0)
        require(maxCharacters in 1..VectorMemoryRetriever.MAX_CHARACTERS)
    }

    suspend fun retrieveForQuery(query: String): VectorMemoryContextResult {
        if (query.isBlank()) return VectorMemoryContextResult.Unavailable(
            VectorMemoryContextResult.Reason.INVALID_QUERY_VECTOR,
        )

        val snapshot = when (val loaded = snapshots.loadSnapshot()) {
            VectorSnapshotReadResult.Missing -> return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.SNAPSHOT_MISSING,
            )
            is VectorSnapshotReadResult.Corrupt -> return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.SNAPSHOT_CORRUPT,
            )
            is VectorSnapshotReadResult.Incompatible -> return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.SNAPSHOT_INCOMPATIBLE,
            )
            is VectorSnapshotReadResult.Loaded -> loaded.snapshot
        }

        val identifiedRuntime = embeddings as? IdentifiedQueryEmbeddingProvider
        if (identifiedRuntime != null &&
            (identifiedRuntime.modelId != snapshot.modelId ||
                identifiedRuntime.dimensions != snapshot.dimensions)
        ) {
            return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.SNAPSHOT_INCOMPATIBLE,
            )
        }

        val queryVector = try {
            embeddings.embed(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.EMBEDDING_FAILED,
            )
        }
        if (queryVector.size != snapshot.dimensions ||
            queryVector.isEmpty() ||
            queryVector.any { !it.isFinite() } ||
            queryVector.all { it == 0f }
        ) {
            return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.INVALID_QUERY_VECTOR,
            )
        }

        val texts = try {
            textProvider.loadMemoryTexts()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.SOURCE_TEXT_UNAVAILABLE,
            )
        }
        val retrieved = try {
            VectorMemoryRetriever.retrieve(
                snapshot = snapshot,
                queryVector = queryVector,
                texts = texts,
                limit = resultLimit,
                minimumScore = minimumScore,
                maxCharacters = maxCharacters,
                queryText = query,
            )
        } catch (_: IllegalArgumentException) {
            return VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.SOURCE_TEXT_UNAVAILABLE,
            )
        }
        return if (retrieved.isEmpty()) {
            VectorMemoryContextResult.NoRelevantMemories
        } else {
            VectorMemoryContextResult.Ready(retrieved)
        }
    }
}
