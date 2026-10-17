package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase

/**
 * Narrow engine boundary for a real local EmbeddingGemma 2 implementation.
 * Implementations must return native 768-dimensional outputs and an explicit backend evidence level.
 * A configured backend label alone must not be presented as proof of device-level execution.
 */
interface EmbeddingGemma2InferenceEngine {
    val runtimeId: String
    suspend fun infer(formattedInputs: List<String>): EmbeddingBatch
}

/**
 * Applies model-specific query/document formatting and output normalization around an actual engine.
 * This adapter itself does not load a model, download assets, or claim a backend is available.
 */
class EmbeddingGemma2RuntimeAdapter(
    private val engine: EmbeddingGemma2InferenceEngine,
    private val outputDimensions: Int = EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS,
    configuredBatchSize: Int = 16,
) : LocalEmbeddingRuntime {
    companion object {
        const val MAX_CONFIGURED_BATCH_SIZE = 256
        const val MAX_FORMATTED_INPUT_BYTES = 4096
    }

    init {
        require(outputDimensions in EmbeddingGemma2TextPolicy.supportedDimensions) {
            "Unsupported embedding output dimension for EmbeddingGemma 2: $outputDimensions. " +
                "Choose 128, 256, 512, or 768."
        }
        require(engine.runtimeId.isNotBlank()) { "Engine runtime ID must not be blank." }
        require(configuredBatchSize in 1..MAX_CONFIGURED_BATCH_SIZE) {
            "Configured embedding batch size must be between 1 and $MAX_CONFIGURED_BATCH_SIZE."
        }
    }

    override val model = EmbeddingModelSpec(
        modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
        dimensions = outputDimensions,
        maxBatchSize = configuredBatchSize,
        localOnly = true,
    )
    override val runtimeId: String = engine.runtimeId

    override suspend fun embed(texts: List<String>): EmbeddingBatch = embedDocuments(texts)

    suspend fun embedQueries(queries: List<String>): EmbeddingBatch =
        inferAndValidate(queries) { text, _ -> EmbeddingGemma2TextPolicy.formatSearchQuery(text) }

    suspend fun embedDocuments(texts: List<String>, titles: List<String?>? = null): EmbeddingBatch {
        if (titles != null && titles.size != texts.size) {
            throw EmbeddingOutputException("Document title count must match input count.")
        }
        return inferAndValidate(texts) { text, index ->
            EmbeddingGemma2TextPolicy.formatDocument(text, titles?.getOrNull(index))
        }
    }

    private suspend fun inferAndValidate(
        texts: List<String>,
        formatter: (String, Int) -> String,
    ): EmbeddingBatch {
        if (texts.isEmpty()) throw EmbeddingOutputException("Input batch must not be empty.")
        if (texts.size > model.maxBatchSize) {
            throw EmbeddingOutputException("Input batch exceeds the configured limit.")
        }
        if (texts.any(String::isBlank)) throw EmbeddingOutputException("Input text must not be blank.")
        val formattedInputs = texts.mapIndexed { index, text -> formatter(text, index) }
        formattedInputs.forEachIndexed { index, formatted ->
            if (formatted.toByteArray(Charsets.UTF_8).size > MAX_FORMATTED_INPUT_BYTES) {
                throw EmbeddingOutputException(
                    "Formatted input $index exceeds the $MAX_FORMATTED_INPUT_BYTES-byte model input limit; split the text into smaller passages.",
                )
            }
        }
        val raw = engine.infer(formattedInputs)
        if (raw.vectors.size != texts.size ||
            raw.vectors.any { it.size != EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS }
        ) {
            throw EmbeddingOutputException("Engine must return one native 768-dimensional vector per input.")
        }
        if (raw.status.modelId != EmbeddingGemma2TextPolicy.MODEL_ID) {
            throw EmbeddingOutputException("Engine status does not identify EmbeddingGemma 2.")
        }
        if (raw.status.runtimeId != engine.runtimeId ||
            raw.status.runtimeId.isNullOrBlank() ||
            raw.status.backend == InferenceBackend.UNKNOWN ||
            raw.status.backendEvidence == BackendEvidence.NOT_AVAILABLE
        ) {
            throw EmbeddingOutputException("Engine did not report a runtime ID and backend evidence.")
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
        return EmbeddingBatch(vectors, raw.status)
    }
}
