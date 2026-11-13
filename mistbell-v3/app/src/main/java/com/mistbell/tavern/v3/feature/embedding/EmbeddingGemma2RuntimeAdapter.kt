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
        const val MAX_FORMATTED_INPUT_BYTES = EmbeddingGemma2TextPolicy.MAX_SAFE_FORMATTED_INPUT_BYTES
        const val BATCH_SIZE = 8

        private fun isUsableCache(
            entry: EmbeddingVectorCache.Entry?,
            dimensions: Int,
        ): Boolean =
            entry != null &&
                entry.vector.size == dimensions &&
                entry.status.modelId == EmbeddingGemma2TextPolicy.MODEL_ID &&
                entry.policyVersion == EmbeddingGemma2TextPolicy.POLICY_VERSION &&
                !entry.status.runtimeId.isNullOrBlank() &&
                entry.status.backendEvidence != BackendEvidence.NOT_AVAILABLE &&
                (
                    entry.status.phase == InferencePhase.READY ||
                        entry.status.phase == InferencePhase.RUNNING ||
                        entry.status.phase == InferencePhase.FALLBACK
                )

        private fun nativeCacheKey(
            formatted: String,
            modelProfileId: String,
        ): String =
            EmbeddingVectorCache.key(
                formatted,
                EmbeddingGemma2TextPolicy.MODEL_ID,
                EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS,
                EmbeddingGemma2TextPolicy.POLICY_VERSION,
                modelProfileId,
            )

        private fun cachedNativeEntry(
            formatted: String,
            modelProfileId: String,
        ): EmbeddingVectorCache.Entry? {
            val nativeKey = nativeCacheKey(formatted, modelProfileId)
            val native = EmbeddingVectorCache.get(nativeKey)
            return if (isUsableCache(native, EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS)) {
                native
            } else {
                if (native != null) EmbeddingVectorCache.remove(nativeKey)
                null
            }
        }

        /** Returns a cached query result without acquiring or initializing a native engine. */
        fun cachedQuery(
            query: String,
            outputDimensions: Int = EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS,
            modelProfileId: String = EmbeddingGemma2TextPolicy.MODEL_ID,
            onDiagnostic: (String, String) -> Unit = { event, detail -> EmbeddingVectorCache.log(event, detail) },
        ): EmbeddingBatch? {
            if (query.isBlank() || outputDimensions !in EmbeddingGemma2TextPolicy.supportedDimensions) return null
            val formatted = EmbeddingGemma2TextPolicy.formatSearchQuery(query)
            val key =
                EmbeddingVectorCache.key(
                    formatted,
                    EmbeddingGemma2TextPolicy.MODEL_ID,
                    outputDimensions,
                    EmbeddingGemma2TextPolicy.POLICY_VERSION,
                    modelProfileId,
                )
            val cached = EmbeddingVectorCache.get(key)
            if (isUsableCache(cached, outputDimensions)) {
                onDiagnostic(
                    "embedding.cache.hit",
                    "modelId=${EmbeddingGemma2TextPolicy.MODEL_ID}; dimensions=$outputDimensions; " +
                        "engineInitializationSkipped=true",
                )
                return EmbeddingBatch(
                    listOf(cached!!.vector),
                    cached.status.copy(lastExecutionMillis = null, isCachedResult = true),
                )
            }
            if (cached != null) EmbeddingVectorCache.remove(key)
            if (outputDimensions < EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) {
                val native = cachedNativeEntry(formatted, modelProfileId)
                if (native != null) {
                    val vector = EmbeddingGemma2TextPolicy.truncateAndNormalize(native.vector, outputDimensions)
                    EmbeddingVectorCache.put(key, vector, native.status, EmbeddingGemma2TextPolicy.POLICY_VERSION)
                    onDiagnostic(
                        "embedding.cache.hit",
                        "modelId=${EmbeddingGemma2TextPolicy.MODEL_ID}; dimensions=$outputDimensions; " +
                            "parentDimensions=${EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS}; " +
                            "engineInitializationSkipped=true",
                    )
                    return EmbeddingBatch(
                        listOf(vector),
                        native.status.copy(lastExecutionMillis = null, isCachedResult = true),
                    )
                }
            }
            return null
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

    override val model =
        EmbeddingModelSpec(
            modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
            dimensions = outputDimensions,
            maxBatchSize = configuredBatchSize,
            localOnly = true,
        )
    override val runtimeId: String = engine.runtimeId

    override suspend fun embed(texts: List<String>): EmbeddingBatch = embedDocuments(texts)

    suspend fun embedQueries(queries: List<String>): EmbeddingBatch =
        inferAndValidate(queries) { text, _ -> EmbeddingGemma2TextPolicy.formatSearchQuery(text) }

    suspend fun embedDocuments(
        texts: List<String>,
        titles: List<String?>? = null,
    ): EmbeddingBatch {
        if (titles != null && titles.size != texts.size) {
            throw EmbeddingOutputException("Document title count must match input count.")
        }
        return inferAndValidate(texts) { text, index ->
            EmbeddingGemma2TextPolicy.formatDocument(text, titles?.getOrNull(index))
        }
    }

    suspend fun embedByTask(
        texts: List<String>,
        task: EmbeddingTaskType,
        titles: List<String?>? = null,
    ): EmbeddingBatch {
        if (titles != null && titles.size != texts.size) {
            throw EmbeddingOutputException("Task title count must match input count.")
        }
        return inferAndValidate(texts) { text, index ->
            EmbeddingGemma2TextPolicy.formatTask(text, task, titles?.getOrNull(index))
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

        val formattedInputs =
            texts.mapIndexed { index, original ->
                val bounded = EmbeddingGemma2TextPolicy.truncateInput(original)
                val formatted = formatter(bounded, index)
                val safeFormatted = EmbeddingGemma2TextPolicy.truncateFormattedInput(formatted)
                // The formatter may already enforce the budget, so also compare the surviving
                // payload with the input: a shortened payload means content was dropped.
                val payloadDropped = !safeFormatted.endsWith(bounded.trim())
                if (safeFormatted.length < formatted.length || bounded.length < original.length || payloadDropped) {
                    onDiagnostic(
                        "embedding.input.truncated",
                        "originalLength=${original.length}; truncatedLength=${safeFormatted.length}",
                    )
                }
                safeFormatted
            }
        formattedInputs.forEachIndexed { index, formatted ->
            if (formatted.toByteArray(Charsets.UTF_8).size > MAX_FORMATTED_INPUT_BYTES) {
                throw EmbeddingOutputException(
                    "Formatted input $index exceeds the $MAX_FORMATTED_INPUT_BYTES-byte model input limit; split the" +
                        " text into smaller passages.",
                )
            }
        }

        val output = arrayOfNulls<List<Float>>(texts.size)
        val pendingByKey = linkedMapOf<String, MutableList<Int>>()
        val cachedStatuses = mutableListOf<com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus>()
        formattedInputs.forEachIndexed { index, formatted ->
            val key =
                EmbeddingVectorCache.key(
                    formatted,
                    model.modelId,
                    model.dimensions,
                    EmbeddingGemma2TextPolicy.POLICY_VERSION,
                    engine.cacheProfileId,
                )
            val cached = EmbeddingVectorCache.get(key)
            if (isUsableCache(cached, model.dimensions)) {
                onDiagnostic("embedding.cache.hit", "modelId=${model.modelId}; dimensions=${model.dimensions}")
                output[index] = cached!!.vector
                cachedStatuses += cached.status.copy(isCachedResult = true)
            } else {
                if (cached != null) EmbeddingVectorCache.remove(key)
                val native = nativeCacheEntry(formatted)
                if (native != null) {
                    val vector = EmbeddingGemma2TextPolicy.truncateAndNormalize(native.vector, model.dimensions)
                    EmbeddingVectorCache.put(key, vector, native.status, EmbeddingGemma2TextPolicy.POLICY_VERSION)
                    onDiagnostic(
                        "embedding.cache.hit",
                        "modelId=${model.modelId}; dimensions=${model.dimensions}; " +
                            "parentDimensions=${EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS}",
                    )
                    output[index] = vector
                    cachedStatuses += native.status.copy(isCachedResult = true)
                } else {
                    onDiagnostic("embedding.cache.miss", "modelId=${model.modelId}; dimensions=${model.dimensions}")
                    pendingByKey.getOrPut(key) { mutableListOf() }.add(index)
                }
            }
        }

        var lastStatus: com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus? = null
        for (batch in pendingByKey.entries.toList().chunked(BATCH_SIZE)) {
            val raw = engine.infer(batch.map { (_, indexes) -> formattedInputs[indexes.first()] })
            validateRawBatch(raw, batch.size)
            batch.forEachIndexed { resultIndex, (key, indexes) ->
                val formatted = formattedInputs[indexes.first()]
                val nativeKey = nativeCacheKey(formatted, engine.cacheProfileId)
                val nativeVector =
                    EmbeddingGemma2TextPolicy.truncateAndNormalize(
                        raw.vectors[resultIndex],
                        EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS,
                    )
                EmbeddingVectorCache.put(nativeKey, nativeVector, raw.status, EmbeddingGemma2TextPolicy.POLICY_VERSION)
                val vector =
                    if (outputDimensions == EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) {
                        nativeVector
                    } else {
                        EmbeddingGemma2TextPolicy.truncateAndNormalize(nativeVector, outputDimensions)
                    }
                if (key != nativeKey) {
                    EmbeddingVectorCache.put(key, vector, raw.status, EmbeddingGemma2TextPolicy.POLICY_VERSION)
                }
                indexes.forEach { originalIndex -> output[originalIndex] = vector }
            }
            lastStatus = raw.status
        }

        val status =
            lastStatus ?: cachedStatuses.firstOrNull()?.copy(lastExecutionMillis = null, isCachedResult = true)
                ?: throw EmbeddingOutputException("Embedding cache did not provide a usable result status.")
        return EmbeddingBatch(
            vectors =
                output.mapIndexed { index, vector ->
                    vector ?: throw EmbeddingOutputException("Embedding result $index was not produced.")
                },
            status = status,
        )
    }

    private fun nativeCacheEntry(formatted: String): EmbeddingVectorCache.Entry? =
        if (outputDimensions >= EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) {
            null
        } else {
            cachedNativeEntry(formatted, engine.cacheProfileId)
        }

    private fun validateRawBatch(
        raw: EmbeddingBatch,
        expectedCount: Int,
    ) {
        if (raw.vectors.size != expectedCount ||
            raw.vectors.any { it.size != EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS }
        ) {
            throw EmbeddingOutputException("Engine must return one native 768-dimensional vector per input.")
        }
        if (raw.status.modelId != EmbeddingGemma2TextPolicy.MODEL_ID) {
            throw EmbeddingOutputException("Engine status does not identify EmbeddingGemma 2.")
        }
        if (raw.status.runtimeId != engine.runtimeId || raw.status.runtimeId.isNullOrBlank() ||
            raw.status.backendEvidence == BackendEvidence.NOT_AVAILABLE
        ) {
            throw EmbeddingOutputException("Engine did not report a runtime ID and backend evidence.")
        }
        if (raw.status.phase != InferencePhase.READY && raw.status.phase != InferencePhase.RUNNING &&
            raw.status.phase != InferencePhase.FALLBACK
        ) {
            throw EmbeddingOutputException("Engine did not report an active inference phase.")
        }
    }
}
