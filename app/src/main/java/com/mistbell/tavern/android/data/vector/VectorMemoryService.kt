package com.mistbell.tavern.android.data.vector

import android.util.Log
import com.mistbell.tavern.android.data.api.ApiCallTrace
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        private const val EMBEDDING_CACHE_INITIAL_CAPACITY = 32
        private const val EMBEDDING_CACHE_LOAD_FACTOR = 0.75f
        private const val EMBEDDING_CACHE_MAX_ENTRIES = 512
        private const val HEX_BYTE_WIDTH = 2
        private const val BYTE_MASK = 0xff
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

    private val embeddingModelId = embeddingService.modelId()
    private val legacyVectorsCompatible = embeddingService.supportsLegacyVectors()
    private val embeddingCache =
        LinkedHashMap<String, FloatArray>(
            EMBEDDING_CACHE_INITIAL_CAPACITY,
            EMBEDDING_CACHE_LOAD_FACTOR,
            true,
        )
    private val embeddingCacheMutex = Mutex()

    /**
     * Reuse the exact text embedding across the write-then-retrieve path.
     * The mutex covers cache lookup and generation so concurrent requests for the same text
     * cannot race through the miss path and issue duplicate embedding API requests.
     */
    private fun embeddingCacheKey(text: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("$embeddingModelId\u0000$text".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> ("%0" + HEX_BYTE_WIDTH + "x").format(byte.toInt() and BYTE_MASK) }
    }

    private suspend fun embedCached(
        text: String,
        source: String,
    ): FloatArray =
        embeddingCacheMutex.withLock {
            val key = embeddingCacheKey(text)
            val cached = synchronized(embeddingCache) { embeddingCache[key]?.copyOf() }
            if (cached != null) {
                ApiCallTrace.log(
                    ApiCallTrace.newId(),
                    source,
                    "CACHE_HIT",
                    "engine=memory model=$embeddingModelId dimension=${cached.size}",
                )
                return@withLock cached
            }
            val generated = embeddingService.embed(text, source)
            synchronized(embeddingCache) {
                embeddingCache[key] = generated.copyOf()
                while (embeddingCache.size > EMBEDDING_CACHE_MAX_ENTRIES) {
                    embeddingCache.remove(embeddingCache.keys.first())
                }
            }
            generated.copyOf()
        }

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
            val embedding = embedCached(content, "EMBEDDING_STORE_${contentType.uppercase()}")

            // 2. 构建元数据
            val metadata =
                mapOf(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                    "session_id" to sessionId,
                    "message_id" to messageId,
                    "content_type" to contentType,
                    "content" to content,
                    "embedding_model" to embeddingModelId,
                    "created_at" to System.currentTimeMillis().toString(),
                )

            // 3. 生成向量ID
            val vectorId = generateVectorId()

            // 4. 存储到向量数据库
            vectorStore.add(vectorId, embedding, metadata)

            Log.d(TAG, "Stored message vector: $messageId -> $vectorId")

            return vectorId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store message vector (${e.javaClass.simpleName})")
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
        if (topK <= 0) return emptyList()
        try {
            // 1. 查询向量化
            val queryEmbedding = embedCached(query, "EMBEDDING_MEMORY_QUERY")

            // 2. 构建基础过滤器；模型兼容性在结果层处理，以保留可安全复用的旧向量。
            val filters =
                mutableMapOf<String, Any>(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                )

            // 如果指定了会话ID，则只检索当前会话
            if (sessionId != null) {
                filters["session_id"] = sessionId
            }

            // Retrieve all candidates so incompatible model vectors cannot crowd legacy matches
            // out of the top-K window. InMemoryVectorStore filters incompatible dimensions first.
            val candidateLimit = maxOf(topK, vectorStore.count())
            val candidates = vectorStore.search(queryEmbedding, candidateLimit, filters)
            val results =
                candidates
                    .filter { result ->
                        if (result.metadata.containsKey("embedding_model")) {
                            result.metadata["embedding_model"]?.toString() == embeddingModelId
                        } else {
                            legacyVectorsCompatible
                        }
                    }.take(topK)

            Log.d(TAG, "Search found ${results.size} relevant memories for queryLength=${query.length}")

            return results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search relevant memories (${e.javaClass.simpleName})")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session vectors (${e.javaClass.simpleName})")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete character vectors (${e.javaClass.simpleName})")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete vectors by messageId (${e.javaClass.simpleName})")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete summary vectors (${e.javaClass.simpleName})")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get statistics (${e.javaClass.simpleName})")
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
