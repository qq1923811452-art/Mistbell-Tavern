package com.mistbell.tavern.android.data.vector

/**
 * Runtime facts shown in the chat UI. Backend is reported only after the engine initialized;
 * lastInferenceSucceeded changes only after a real embedding request returns a valid vector.
 */
data class EmbeddingRuntimeStatus(
    val source: String = "auto",
    val backend: String? = null,
    val modelVariant: String? = null,
    val initialized: Boolean = false,
    val successfulInferenceCount: Long = 0,
    val lastInferenceAtMillis: Long? = null,
    val lastInferenceSucceeded: Boolean? = null,
    val errorMessage: String? = null,
)

/**
 * 嵌入服务接口
 *
 * 用于将文本转换为向量表示
 * 移植自后端 EmbeddingService.java
 */
interface EmbeddingService {
    /** True only when this service produces real semantic embeddings, not placeholders. */
    val isSemantic: Boolean
        get() = false

    /**
     * Stable identity for the vector space produced by this implementation.
     * Include model and endpoint identity when a provider can be configured.
     */
    fun modelId(): String = this::class.java.name

    /** Whether untagged legacy vectors are known to belong to this exact vector space. */
    fun supportsLegacyVectors(): Boolean = false

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量数组；具体维度由所选模型决定
     */
    suspend fun embed(text: String): FloatArray

    /** Same operation with a diagnostic source label; default preserves existing implementations. */
    suspend fun embed(
        text: String,
        source: String,
    ): FloatArray = embed(text)

    /** Explicit role hooks; asymmetric embedding models can apply the correct task instruction. */
    suspend fun embedQuery(text: String, source: String): FloatArray = embed(text, source)

    suspend fun embedDocument(text: String, source: String): FloatArray = embed(text, source)

    /**
     * 批量转换文本为向量
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray>

    /** Batch variant with a diagnostic source label. */
    suspend fun embedBatch(
        texts: List<String>,
        source: String,
    ): List<FloatArray> = embedBatch(texts)

    /**
     * 获取向量维度。远程服务在首次成功响应前返回 0，之后返回实际维度。
     */
    fun getDimension(): Int
}
