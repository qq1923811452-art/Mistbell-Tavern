package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferencePhase

/**
 * Narrow engine boundary for a real local EmbeddingGemma 2 implementation.
 * Implementations must return native 768-dimensional outputs and an explicit backend evidence level.
 * A configured backend label alone must not be presented as proof of device-level execution.
 */
interface EmbeddingGemma2InferenceEngine {
    val runtimeId: String
    /** Stable model-profile identity; native adapters should include the installed model file identity. */
    val cacheProfileId: String get() = EmbeddingGemma2TextPolicy.MODEL_ID
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
    private val onDiagnostic: (String, String) -> Unit = { event, detail -> EmbeddingVectorCache.log(event, detail) },
) : LocalEmbeddingRuntime {
    companion object {
        const val MAX_CONFIGURED_BATCH_SIZE = 256
        const val MAX_FORMATTED_INPUT_BYTES = 4096
        const val BATCH_SIZE = 8

        /** Returns a cached query result without acquiring or initializing a native engine. */
        fun cachedQuery(
            query: String,
            outputDimensions: Int = EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS,
            modelProfileId: String = EmbeddingGemma2TextPolicy.MODEL_ID,
            onDiagnostic: (String, String) -> Unit = { event, detail -> EmbeddingVectorCache.log(event, detail) },
        ): EmbeddingBatch? {
            if (query.isBlank() || outputDimensions !in EmbeddingGemma2TextPolicy.supportedDimensions) return null
            val formatted = EmbeddingGemma2TextPolicy.formatSearchQuery(EmbeddingGemma2TextPolicy.truncateInput(query))
            val key = EmbeddingVectorCache.key(formatted, EmbeddingGemma2TextPolicy.MODEL_ID, outputDimensions, EmbeddingGemma2TextPolicy.POLICY_VERSION, modelProfileId)
            val cached = EmbeddingVectorCache.get(key) ?: return null
            val usable = cached.vector.size == outputDimensions &&
                cached.status.modelId == EmbeddingGemma2TextPolicy.MODEL_ID &&
                cached.policyVersion == EmbeddingGemma2TextPolicy.POLICY_VERSION &&
                !cached.status.runtimeId.isNullOrBlank() &&
                cached.status.backendEvidence != BackendEvidence.NOT_AVAILABLE &&
                (cached.status.phase == InferencePhase.READY || cached.status.phase == InferencePhase.RUNNING ||
                    cached.status.phase == InferencePhase.FALLBACK)
            if (!usable) {
                EmbeddingVectorCache.remove(key)
                return null
            }
            onDiagnostic("embedding.cache.hit", "modelId=${EmbeddingGemma2TextPolicy.MODEL_ID}; dimensions=$outputDimensions; engineInitializationSkipped=true")
            return EmbeddingBatch(listOf(cached.vector), cached.status.copy(lastExecutionMillis = null, isCachedResult = true))
        }
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

    suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        if (texts.any(String::isBlank)) throw EmbeddingOutputException("Input text must not be blank.")
        val result = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(BATCH_SIZE)) {
            result += embedDocuments(chunk).vectors.map { it.toFloatArray() }
        }
        return result
    }

    private suspend fun inferAndValidate(
        texts: List<String>,
        formatter: (String, Int) -> String,
    ): EmbeddingBatch {
        if (texts.isEmpty()) throw EmbeddingOutputException("Input batch must not be empty.")
        if (texts.size > model.maxBatchSize) throw EmbeddingOutputException("Input batch exceeds the configured limit.")
        if (texts.any(String::isBlank)) throw EmbeddingOutputException("Input text must not be blank.")

        val formattedInputs = texts.mapIndexed { index, original ->
            val bounded = EmbeddingGemma2TextPolicy.truncateInput(original)
            if (bounded.length < original.length) {
                onDiagnostic("embedding.input.truncated", "originalLength=${original.length}; truncatedLength=${bounded.length}")
            }
            formatter(bounded, index)
        }
        formattedInputs.forEachIndexed { index, formatted ->
            if (formatted.toByteArray(Charsets.UTF_8).size > MAX_FORMATTED_INPUT_BYTES) {
                throw EmbeddingOutputException(
                    "Formatted input $index exceeds the $MAX_FORMATTED_INPUT_BYTES-byte model input limit; split the text into smaller passages.",
                )
            }
        }

        val output = arrayOfNulls<List<Float>>(texts.size)
        val pendingByKey = linkedMapOf<String, MutableList<Int>>()
        val cachedStatuses = mutableListOf<com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus>()
        formattedInputs.forEachIndexed { index, formatted ->
            val key = EmbeddingVectorCache.key(formatted, model.modelId, model.dimensions, EmbeddingGemma2TextPolicy.POLICY_VERSION, engine.cacheProfileId)
            val cached = EmbeddingVectorCache.get(key)
            val usableCached = cached?.takeIf {
                it.vector.size == model.dimensions &&
                    it.status.modelId == model.modelId &&
                    it.policyVersion == EmbeddingGemma2TextPolicy.POLICY_VERSION &&
                    !it.status.runtimeId.isNullOrBlank() &&
                    it.status.backendEvidence != BackendEvidence.NOT_AVAILABLE &&
                    (it.status.phase == InferencePhase.READY ||
                        it.status.phase == InferencePhase.RUNNING ||
                        it.status.phase == InferencePhase.FALLBACK)
            }
            if (usableCached != null) {
                onDiagnostic("embedding.cache.hit", "modelId=${model.modelId}; dimensions=${model.dimensions}")
                output[index] = usableCached.vector
                cachedStatuses += usableCached.status.copy(isCachedResult = true)
            } else {
                if (cached != null) EmbeddingVectorCache.remove(key)
                onDiagnostic("embedding.cache.miss", "modelId=${model.modelId}; dimensions=${model.dimensions}")
                pendingByKey.getOrPut(key) { mutableListOf() }.add(index)
            }
        }

        var lastStatus: com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus? = null
        for (batch in pendingByKey.entries.toList().chunked(BATCH_SIZE)) {
            val raw = engine.infer(batch.map { (_, indexes) -> formattedInputs[indexes.first()] })
            validateRawBatch(raw, batch.size)
            val normalized = raw.vectors.map {
                EmbeddingGemma2TextPolicy.truncateAndNormalize(it, outputDimensions)
            }
            batch.forEachIndexed { resultIndex, (key, indexes) ->
                val vector = normalized[resultIndex]
                EmbeddingVectorCache.put(key, vector, raw.status, EmbeddingGemma2TextPolicy.POLICY_VERSION)
                indexes.forEach { originalIndex -> output[originalIndex] = vector }
            }
            lastStatus = raw.status
        }

        val status = lastStatus ?: cachedStatuses.firstOrNull()?.copy(lastExecutionMillis = null, isCachedResult = true)
            ?: throw EmbeddingOutputException("Embedding cache did not provide a usable result status.")
        return EmbeddingBatch(
            vectors = output.mapIndexed { index, vector ->
                vector ?: throw EmbeddingOutputException("Embedding result $index was not produced.")
            },
            status = status,
        )
    }

    private fun validateRawBatch(raw: EmbeddingBatch, expectedCount: Int) {
        if (raw.vectors.size != expectedCount ||
            raw.vectors.any { it.size != EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS }
        ) throw EmbeddingOutputException("Engine must return one native 768-dimensional vector per input.")
        if (raw.status.modelId != EmbeddingGemma2TextPolicy.MODEL_ID) {
            throw EmbeddingOutputException("Engine status does not identify EmbeddingGemma 2.")
        }
        if (raw.status.runtimeId != engine.runtimeId || raw.status.runtimeId.isNullOrBlank() ||
            raw.status.backendEvidence == BackendEvidence.NOT_AVAILABLE
        ) throw EmbeddingOutputException("Engine did not report a runtime ID and backend evidence.")
        if (raw.status.phase != InferencePhase.READY && raw.status.phase != InferencePhase.RUNNING &&
            raw.status.phase != InferencePhase.FALLBACK
        ) throw EmbeddingOutputException("Engine did not report an active inference phase.")
    }

}
