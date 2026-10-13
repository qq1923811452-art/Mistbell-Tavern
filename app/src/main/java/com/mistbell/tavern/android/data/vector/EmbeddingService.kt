package com.mistbell.tavern.android.data.vector

/**
 * 嵌入服务接口
 *
 * 用于将文本转换为向量表示
 * 移植自后端 EmbeddingService.java
 */
interface EmbeddingService {
    /**
     * Stable identity for the vector space produced by this implementation.
     * Include model and endpoint identity when a provider can be configured.
     */
    fun modelId(): String = this::class.java.name

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量数组（通常是 1536 维）
     */
    suspend fun embed(text: String): FloatArray

    /** Same operation with a diagnostic source label; default preserves existing implementations. */
    suspend fun embed(
        text: String,
        source: String,
    ): FloatArray = embed(text)

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
     * 获取向量维度
     */
    fun getDimension(): Int
}
