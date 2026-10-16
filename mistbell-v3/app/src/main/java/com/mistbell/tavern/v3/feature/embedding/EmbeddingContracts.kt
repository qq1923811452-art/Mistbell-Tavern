package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus

/** Candidate metadata only; it does not claim that a model is installed or runnable. */
data class EmbeddingModelSpec(
    val modelId: String,
    val dimensions: Int,
    val maxBatchSize: Int,
    val localOnly: Boolean = true,
) {
    init {
        require(modelId.isNotBlank()) { "Model ID must not be blank." }
        require(dimensions in 1..4096) { "Embedding dimensions are outside the supported range." }
        require(maxBatchSize in 1..256) { "Embedding batch size is outside the supported range." }
    }
}

data class EmbeddingBatch(val vectors: List<List<Float>>, val status: InferenceRuntimeStatus) {
    init {
        require(status.phase == InferencePhase.READY || status.phase == InferencePhase.RUNNING ||
            status.phase == InferencePhase.FALLBACK) {
            "Embedding result must include an active runtime status."
        }
        require(vectors.isNotEmpty()) { "Embedding result must not be empty." }
    }
}

/** A runtime adapter must report the backend used by completed inference, not just the requested one. */
interface LocalEmbeddingRuntime {
    val model: EmbeddingModelSpec
    val runtimeId: String
    suspend fun embed(texts: List<String>): EmbeddingBatch
}

data class ValidatedEmbeddingBatch(
    val modelId: String,
    val dimensions: Int,
    val vectors: List<List<Float>>,
    val status: InferenceRuntimeStatus,
)

class EmbeddingOutputException(message: String) : IllegalStateException(message)

object EmbeddingOutputValidator {
    fun validate(
        runtime: LocalEmbeddingRuntime,
        inputTexts: List<String>,
        output: EmbeddingBatch,
    ): ValidatedEmbeddingBatch {
        if (inputTexts.isEmpty()) throw EmbeddingOutputException("Input batch must not be empty.")
        if (inputTexts.size > runtime.model.maxBatchSize) {
            throw EmbeddingOutputException("Input batch exceeds the model's configured limit.")
        }
        if (inputTexts.any(String::isBlank)) throw EmbeddingOutputException("Input text must not be blank.")
        if (output.vectors.size != inputTexts.size) {
            throw EmbeddingOutputException(
                "Embedding count ${output.vectors.size} does not match input count ${inputTexts.size}.",
            )
        }
        output.vectors.forEachIndexed { index, vector ->
            if (vector.size != runtime.model.dimensions) {
                throw EmbeddingOutputException(
                    "Vector $index has ${vector.size} dimensions; expected ${runtime.model.dimensions}.",
                )
            }
            if (vector.any { !it.isFinite() }) {
                throw EmbeddingOutputException("Vector $index contains a non-finite value.")
            }
            if (vector.all { it == 0f }) {
                throw EmbeddingOutputException("Vector $index is a zero vector.")
            }
        }
        if (output.status.modelId != runtime.model.modelId) {
            throw EmbeddingOutputException("Runtime status model ID does not match the configured model.")
        }
        if (output.status.runtimeId != runtime.runtimeId) {
            throw EmbeddingOutputException("Runtime status ID does not match the selected adapter.")
        }
        if (output.status.backend == InferenceBackend.UNKNOWN ||
            output.status.backendEvidence == BackendEvidence.NOT_AVAILABLE
        ) {
            throw EmbeddingOutputException("Runtime did not report backend evidence for this execution.")
        }
        return ValidatedEmbeddingBatch(
            modelId = runtime.model.modelId,
            dimensions = runtime.model.dimensions,
            vectors = output.vectors.map { it.toList() },
            status = output.status,
        )
    }
}
