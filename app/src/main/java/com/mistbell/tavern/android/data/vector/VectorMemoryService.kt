package com.mistbell.tavern.android.data.vector

import android.util.Log
import java.util.UUID

/**
 * 向量记忆服务
 *
 * 移植自后端 VectorMemoryService.java
 *
 * 负责：
 * 1. 存储消息到向量数据库
 * 2. 语义检索相关记忆
 */
class VectorMemoryService(
    private val vectorStore: VectorStore,
    private val embeddingService: EmbeddingService,
) {
    companion object {
        private const val TAG = "VectorMemoryService"
    }

    /**
     * 向量能力可用性开关
     *
     * 由构造时注入的 embedding 服务决定：
     * - 真实 API embedding 服务（OpenAIEmbeddingService）→ true
     * - 本地语义向量（LocalEmbeddingService，F3 ONNX）→ true
     * - 占位服务（Mock 等）→ false
     *
     * available=false 时调用方应跳过向量写入/检索，改走词法回退（LexicalMemoryService）。
     */
    val available: Boolean =
        embeddingService is OpenAIEmbeddingService || embeddingService is LocalEmbeddingService

    /**
     * 存储消息到向量数据库
     *
     * @param content 消息内容
     * @param ownerId 用户ID
     * @param characterId 角色ID
     * @param sessionId 会话ID
     * @param messageId 消息ID
     * @param contentType 内容类型（使用 VectorMemory.ContentType 中的常量）
     * @return 向量ID
     */
    suspend fun storeMessage(
        content: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        contentType: String,
    ): String {
        try {
            // 1. 生成向量
            val embedding = embeddingService.embed(content, "EMBEDDING_STORE_${contentType.uppercase()}")

            // 2. 构建元数据
            val metadata =
                mapOf(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                    "session_id" to sessionId,
                    "message_id" to messageId,
                    "content_type" to contentType,
                    "content" to content,
                    "created_at" to System.currentTimeMillis().toString(),
                )

            // 3. 生成向量ID
            val vectorId = generateVectorId()

            // 4. 存储到向量数据库
            vectorStore.add(vectorId, embedding, metadata)

            Log.d(TAG, "Stored message vector: $messageId -> $vectorId")

            return vectorId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store message vector: ${e.message}", e)
            throw e
        }
    }

    /**
     * 搜索相关记忆
     *
     * @param query 查询文本
     * @param ownerId 用户ID
     * @param characterId 角色ID
     * @param sessionId 会话ID（null表示跨会话检索）
     * @param topK 返回前K个结果
     * @return 搜索结果列表
     */
    suspend fun searchRelevantMemories(
        query: String,
        ownerId: String,
        characterId: String,
        sessionId: String?,
        topK: Int = 5,
    ): List<VectorStore.SearchResult> {
        try {
            // 1. 查询向量化
            val queryEmbedding = embeddingService.embed(query, "EMBEDDING_MEMORY_QUERY")

            // 2. 构建过滤器
            val filters =
                mutableMapOf<String, Any>(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                )

            // 如果指定了会话ID，则只检索当前会话
            if (sessionId != null) {
                filters["session_id"] = sessionId
            }

            // 3. 向量检索
            val results = vectorStore.search(queryEmbedding, topK, filters)

            Log.d(TAG, "Search found ${results.size} relevant memories for queryLength=${query.length}")

            return results
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search relevant memories: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * 删除会话的所有向量
     */
    suspend fun deleteSessionVectors(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Int {
        try {
            val filters =
                mapOf(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                    "session_id" to sessionId,
                )

            val deleted = vectorStore.deleteByFilters(filters)
            Log.d(TAG, "Deleted $deleted vectors for session: $sessionId")

            return deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session vectors: ${e.message}", e)
            return 0
        }
    }

    /**
     * 删除角色的所有向量
     */
    suspend fun deleteCharacterVectors(
        ownerId: String,
        characterId: String,
    ): Int {
        try {
            val filters =
                mapOf(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                )

            val deleted = vectorStore.deleteByFilters(filters)
            Log.d(TAG, "Deleted $deleted vectors for character: $characterId")

            return deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete character vectors: ${e.message}", e)
            return 0
        }
    }

    /**
     * 按 messageId 删除向量
     *
     * 结构化记忆删除/更新时清理其同步副本（messageId = "structured_memory_<id>"）
     */
    suspend fun deleteByMessageId(messageId: String): Int {
        return try {
            val deleted = vectorStore.deleteByFilters(mapOf("message_id" to messageId))
            Log.d(TAG, "Deleted $deleted vectors by messageId: $messageId")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete vectors by messageId: ${e.message}", e)
            0
        }
    }

    /**
     * 删除摘要型向量（结构化记忆的同步产物，content_type = summary）
     *
     * 随记忆批量删除联动，按 owner/角色/会话范围过滤
     */
    suspend fun deleteSummaryVectors(
        ownerId: String,
        characterId: String? = null,
        sessionId: String? = null,
    ): Int {
        return try {
            val filters =
                mutableMapOf<String, Any>(
                    "owner_id" to ownerId,
                    "content_type" to "summary",
                )
            characterId?.let { filters["character_id"] = it }
            sessionId?.let { filters["session_id"] = it }
            val deleted = vectorStore.deleteByFilters(filters)
            Log.d(TAG, "Deleted $deleted summary vectors (owner=$ownerId, character=$characterId, session=$sessionId)")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete summary vectors: ${e.message}", e)
            0
        }
    }

    /**
     * 获取向量存储统计信息
     */
    suspend fun getStatistics(): VectorStatistics {
        return try {
            val count = vectorStore.count()
            VectorStatistics(
                totalVectors = count,
                dimension = embeddingService.getDimension(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get statistics: ${e.message}", e)
            VectorStatistics(0, 0)
        }
    }

    /**
     * 生成向量ID
     */
    private fun generateVectorId(): String {
        return "vec_${UUID.randomUUID().toString().replace("-", "")}"
    }

    /**
     * 向量统计信息
     */
    data class VectorStatistics(
        val totalVectors: Int,
        val dimension: Int,
    )
}
