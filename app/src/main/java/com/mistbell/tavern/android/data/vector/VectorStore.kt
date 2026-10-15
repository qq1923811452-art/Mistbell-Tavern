package com.mistbell.tavern.android.data.vector

/**
 * 向量存储接口
 *
 * 提供向量的增删改查和相似度检索功能
 * 移植自后端 VectorStore.java
 */
interface VectorStore {
    /**
     * 添加向量到存储
     *
     * @param id 向量ID
     * @param vector 向量数据（通常是 1536 维的浮点数组）
     * @param metadata 元数据（包含 content、ownerId、characterId 等）
     */
    suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    )

    /**
     * 批量添加向量
     */
    suspend fun addBatch(entries: List<VectorEntry>)

    /**
     * 搜索相似向量
     *
     * @param queryVector 查询向量
     * @param topK 返回前 K 个最相似的结果
     * @param filters 过滤条件（如 ownerId、characterId、sessionId）
     * @return 搜索结果列表，按相似度降序排列
     */
    suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any> = emptyMap(),
    ): List<SearchResult>

    /**
     * 根据 ID 获取向量
     */
    suspend fun get(id: String): VectorEntry?

    /**
     * Snapshot all persisted entries for explicit maintenance operations such as legacy reindexing.
     * Implementations that cannot provide a reliable snapshot must fail explicitly.
     */
    suspend fun getAllEntries(): List<VectorEntry> =
        throw UnsupportedOperationException("Vector store does not support entry snapshots")

    /**
     * 根据 ID 删除向量
     */
    suspend fun delete(id: String)

    /**
     * 根据过滤条件批量删除
     */
    suspend fun deleteByFilters(filters: Map<String, Any>): Int

    /**
     * 获取存储的向量数量
     */
    suspend fun count(): Int

    /**
     * Preserve corrupt persisted data and recreate an empty store after explicit user confirmation.
     * Returns false when there is no persisted file to recover.
     */
    suspend fun recoverCorruptStorage(): Boolean = false

    /**
     * 清空所有向量
     */
    suspend fun clear()

    /**
     * 向量条目
     */
    data class VectorEntry(
        val id: String,
        val vector: FloatArray,
        val metadata: Map<String, Any>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as VectorEntry

            if (id != other.id) return false
            if (!vector.contentEquals(other.vector)) return false
            if (metadata != other.metadata) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + vector.contentHashCode()
            result = 31 * result + metadata.hashCode()
            return result
        }
    }

    /**
     * 搜索结果
     */
    data class SearchResult(
        val id: String,
        // 相似度分数 (0-1)
        val score: Float,
        val content: String,
        val metadata: Map<String, Any>,
    )
}
