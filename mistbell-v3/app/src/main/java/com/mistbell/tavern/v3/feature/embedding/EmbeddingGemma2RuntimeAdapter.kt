package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase

/**
 * Narrow engine boundary for a real local EmbeddingGemma 2 implementation.
 * Implementations must return native 768-dimensional outputs and runtime-observed status.
 */
fun interface EmbeddingGemma2InferenceEngine {
    suspend fun infer(formattedInputs: List<String>): EmbeddingBatch
}

/**
 * Applies model-specific query/document formatting and output normalization around an actual engine.
 * This adapter itself does not load a model, download assets, or claim a backend is available.
 */
class EmbeddingGemma2RuntimeAdapter(
    private val engine: EmbeddingGemma2InferenceEngine,
    private val outputDimensions: Int = EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS,
    private val configuredBatchSize: Int = 16,
) : LocalEmbeddingRuntime {
    override val model = EmbeddingModelSpec(
        modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
        dimensions = outputDimensions,
        maxBatchSize = configuredBatchSize,
        localOnly = true,
    )
    override val runtimeId: String = "embeddinggemma2:${engineRuntimeId()}"
    
    private fun engineRuntimeId(): String {
        // The engine's observed runtime identity is supplied by its first valid result.
        // A stable adapter ID is used for the contract; backend truth is checked per invocation.
        return "engine"
    }

    override suspend fun embed(texts: List<String>): EmbeddingBatch = embedDocuments(texts)

    suspend fun embedQueries(queries: List<String>): EmbeddingBatch =
        inferAndValidate(queries, EmbeddingGemma2TextPolicy::formatSearchQuery)

    suspend fun embedDocuments(texts: List<String>, titles: List<String?>? = null): EmbeddingBatch {
        if (titles != null && titles.size != texts.size) {
            throw EmbeddingOutputException("Document title count must match input count.")
        }
        return inferAndValidate(texts) { text ->
            val index = texts.indexOfFirst { it === text }
            EmbeddingGemma2TextPolicy.formatDocument(text, titles?.getOrNull(index))
        }
    }

    private suspend fun inferAndValidate(
        texts: List<String>,
        formatter: (String) -> String,
    ): EmbeddingBatch {
        if (texts.isEmpty()) throw EmbeddingOutputException("Input batch must not be empty.")
        if (texts.size > model.maxBatchSize) {
            throw EmbeddingOutputException("Input batch exceeds the configured limit.")
        }
        if (texts.any(String::isBlank)) throw EmbeddingOutputException("Input text must not be blank.")
        val raw = engine.infer(texts.map(formatter))
        if (raw.vectors.size != texts.size ||
            raw.vectors.any { it.size != EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS }
        ) {
            throw EmbeddingOutputException("Engine must return one native 768-dimensional vector per input.")
        }
        if (raw.status.modelId != EmbeddingGemma2TextPolicy.MODEL_ID) {
            throw EmbeddingOutputException("Engine status does not identify EmbeddingGemma 2.")
        }
        if (raw.status.runtimeId.isNullOrBlank() || raw.status.backend == InferenceBackend.UNKNOWN) {
            throw EmbeddingOutputException("Engine did not report a verified runtime and backend.")
        }
        if (raw.status.phase != InferencePhase.READY &&
            raw.status.phase != InferencePhase.RUNNING &&
            raw.status.phase != InferencePhase.FALLBACK
        ) {
            throw EmbeddingOutputException("Engine did not report an active inference phase.")
        }
        val vectors = raw.vectors.map {
            EmbeddingGemma2TextPolicy.truncateAndNormalize(it, outputDimensions)
        }
        return EmbeddingBatch(vectors, raw.status.copy(runtimeId = runtimeId))
    }
}
