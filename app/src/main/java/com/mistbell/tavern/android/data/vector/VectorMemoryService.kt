package com.mistbell.tavern.android.data.vector

import android.util.Log
import com.mistbell.tavern.android.data.api.ApiCallTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private fun recordVectorStorageFailure(
    storageError: MutableStateFlow<String?>,
    error: Exception,
) {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause.message?.contains("refusing to treat it as empty") == true) {
            storageError.value =
                "向量记忆文件读取失败。原文件已保留；请先导出备份或联系支持，不要清空应用数据。"
            return
        }
        cause = cause.cause
    }
}

/**
 * 向量记忆服务
 *
 * 移植自后端 VectorMemoryService.java
 *
 * 负责：
 * 1. 存储消息到向量数据库
 * 2. 语义检索相关记忆
 */
@Suppress("TooManyFunctions")
class VectorMemoryService(
    private val vectorStore: VectorStore,
    private val embeddingService: EmbeddingService,
) {
    companion object {
        private const val TAG = "VectorMemoryService"
        private const val EMBEDDING_CACHE_INITIAL_CAPACITY = 32
        private const val EMBEDDING_CACHE_LOAD_FACTOR = 0.75f
        private const val EMBEDDING_CACHE_MAX_ENTRIES = 512
        private const val EMBEDDING_CACHE_STRIPE_COUNT = 32
        private const val VECTOR_WRITE_STRIPE_COUNT = 32
        // Keep explicit legacy reindex requests bounded to limit peak memory and API batch cost.
        private const val LEGACY_REINDEX_BATCH_SIZE = 32
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

    // Per-key hashing stripes preserve same-text single-flight while allowing unrelated
    // embedding requests (including remote API calls) to proceed concurrently.
    private val embeddingCacheMutexes = Array(EMBEDDING_CACHE_STRIPE_COUNT) { Mutex() }

    // Serialize read/reuse/embed/write for the same stable vector ID while keeping unrelated writes concurrent.
    private val vectorWriteMutexes = Array(VECTOR_WRITE_STRIPE_COUNT) { Mutex() }

    private val _storageError = MutableStateFlow<String?>(null)

    /** Non-destructive diagnostic surfaced in Settings when persisted vectors cannot be loaded. */
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    /** Mutable diagnostic state for the container's confirmed recovery path. */
    internal val storageErrorState: MutableStateFlow<String?>
        get() = _storageError

    /**
     * Reuse the exact text embedding across the write-then-retrieve path.
     * A deterministic stripe lock covers cache lookup and generation for each key, preventing
     * duplicate same-text misses while allowing most unrelated texts to embed concurrently.
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
    ): FloatArray {
        val key = embeddingCacheKey(text)
        val mutex = embeddingCacheMutexes[Math.floorMod(key.hashCode(), embeddingCacheMutexes.size)]
        return mutex.withLock {
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
            val identity = VectorIdentity(ownerId, characterId, sessionId, messageId, contentType, embeddingModelId)
            val vectorId = generateVectorId(identity)
            val writeMutex = vectorWriteMutexes[Math.floorMod(vectorId.hashCode(), vectorWriteMutexes.size)]
            return writeMutex.withLock {
                val existing = vectorStore.get(vectorId)
                if (
                    existing?.metadata?.get("content") == content &&
                    existing.metadata["embedding_model"] == embeddingModelId
                ) {
                    Log.d(TAG, "Reused persisted embedding for message: $messageId")
                    return@withLock vectorId
                }

                // Generate only when this exact message/model version is not already persisted.
                val embedding = embedCached(content, "EMBEDDING_STORE_${contentType.uppercase()}")
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

                // Stable IDs plus a per-ID write stripe make repeated concurrent writes idempotent.
                vectorStore.add(vectorId, embedding, metadata)

                Log.d(TAG, "Stored message vector: $messageId -> $vectorId")
                vectorId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordVectorStorageFailure(_storageError, e)
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
    @Suppress("ReturnCount")
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

            // 2. Filter by scope and embedding model in the store first. This avoids
            // materializing and sorting vectors from unrelated embedding spaces on the common path.
            val filters =
                mutableMapOf<String, Any>(
                    "owner_id" to ownerId,
                    "character_id" to characterId,
                    "embedding_model" to embeddingModelId,
                )

            // 如果指定了会话ID，则只检索当前会话
            if (sessionId != null) {
                filters["session_id"] = sessionId
            }

            val modelResults = vectorStore.search(queryEmbedding, topK, filters)
            val results =
                if (!legacyVectorsCompatible || modelResults.size >= topK) {
                    modelResults
                } else {
                    // Only known-compatible embedding services may reuse legacy vectors without
                    // embedding_model metadata. Fall back to a broad search only when the current
                    // model cannot fill top-K, so legacy entries remain available without taxing
                    // every query. The result-layer check still excludes other model identities.
                    filters.remove("embedding_model")
                    val candidateLimit = maxOf(topK, vectorStore.count())
                    vectorStore.search(queryEmbedding, candidateLimit, filters)
                        .filter { result ->
                            if (result.metadata.containsKey("embedding_model")) {
                                result.metadata["embedding_model"]?.toString() == embeddingModelId
                            } else {
                                true
                            }
                        }.take(topK)
                }

            Log.d(TAG, "Search found ${results.size} relevant memories for queryLength=${query.length}")

            return results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordVectorStorageFailure(_storageError, e)
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
     * Re-embed legacy vectors that have no embedding-model identity.
     *
     * This operation is explicit because remote reindexing may incur API cost. Replacements are
     * persisted before legacy entries are deleted; incomplete metadata is skipped, never guessed.
     */
    suspend fun reindexLegacyVectors(
        batchSize: Int = LEGACY_REINDEX_BATCH_SIZE,
    ): LegacyReindexResult {
        require(batchSize > 0) { "batchSize must be positive" }
        check(embeddingService !is MockEmbeddingService) {
            "Legacy vector reindex requires a real embedding service; mock vectors must never be persisted"
        }
        val legacyEntries =
            vectorStore.getAllEntries().filter {
                it.metadata["embedding_model"]?.toString().isNullOrBlank()
            }
        val preparation = prepareLegacyCandidates(legacyEntries)
        var skipped = preparation.skipped
        var reused = preparation.reused
        var reindexed = 0

        preparation.candidates.chunked(batchSize).forEach { batch ->
            val result = reindexLegacyBatch(batch)
            skipped += result.skipped
            reused += result.reused
            reindexed += result.reindexed
        }

        return LegacyReindexResult(
            scannedLegacy = legacyEntries.size,
            migrated = reused + reindexed,
            skipped = skipped,
            reused = reused,
        )
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private suspend fun prepareLegacyCandidates(
        entries: List<VectorStore.VectorEntry>,
    ): LegacyCandidatePreparation {
        var skipped = 0
        val candidates = mutableListOf<LegacyVectorCandidate>()
        for (entry in entries) {
            val metadata = entry.metadata
            val content = metadata["content"] as? String
            val ownerId = metadata["owner_id"] as? String
            val characterId = metadata["character_id"] as? String
            val sessionId = metadata["session_id"] as? String
            val messageId = metadata["message_id"] as? String
            val contentType = metadata["content_type"] as? String
            if (
                content.isNullOrBlank() || ownerId.isNullOrBlank() || characterId.isNullOrBlank() ||
                sessionId.isNullOrBlank() || messageId.isNullOrBlank() || contentType.isNullOrBlank()
            ) {
                skipped++
                continue
            }
            val targetId =
                generateVectorId(
                    VectorIdentity(
                        ownerId,
                        characterId,
                        sessionId,
                        messageId,
                        contentType,
                        embeddingModelId,
                    ),
                )
            candidates +=
                LegacyVectorCandidate(
                    legacyId = entry.id,
                    targetId = targetId,
                    content = content,
                    metadata =
                        metadata.toMutableMap().apply {
                            put("embedding_model", embeddingModelId)
                        },
                )
        }

        // Multiple historical versions can share the same logical identity. Do not guess which
        // content is authoritative: leave every ambiguous legacy copy untouched for manual review.
        val ambiguousTargetIds =
            candidates.groupingBy { it.targetId }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (ambiguousTargetIds.isNotEmpty()) {
            val ambiguousCount = candidates.count { it.targetId in ambiguousTargetIds }
            candidates.removeAll { it.targetId in ambiguousTargetIds }
            skipped += ambiguousCount
            Log.w(TAG, "Skipped $ambiguousCount legacy vectors with ambiguous logical identities")
        }

        var reused = 0
        val pending = mutableListOf<LegacyVectorCandidate>()
        for (candidate in candidates) {
            val existingTarget = vectorStore.get(candidate.targetId)
            if (
                existingTarget != null &&
                existingTarget.metadata["embedding_model"] == embeddingModelId &&
                existingTarget.metadata["content"] == candidate.content
            ) {
                if (candidate.legacyId != candidate.targetId) {
                    vectorStore.delete(candidate.legacyId)
                }
                reused++
            } else {
                pending += candidate
            }
        }
        return LegacyCandidatePreparation(pending, skipped, reused)
    }

    private suspend fun reindexLegacyBatch(
        batch: List<LegacyVectorCandidate>,
    ): LegacyBatchResult {
        // Reindexing shares stable IDs with normal writes. Hold the bounded stripe set across
        // recheck/embed/persist/delete so a newer message cannot be overwritten between steps.
        val acquiredLocks = mutableListOf<Mutex>()
        try {
            vectorWriteMutexes.forEach { mutex ->
                mutex.lock()
                acquiredLocks += mutex
            }
            return reindexLegacyBatchLocked(batch)
        } finally {
            acquiredLocks.asReversed().forEach { mutex -> mutex.unlock() }
        }
    }

    private suspend fun reindexLegacyBatchLocked(
        batch: List<LegacyVectorCandidate>,
    ): LegacyBatchResult {
        // Recheck target IDs immediately before embedding. Another writer may have stored
        // a newer version after the initial snapshot; never overwrite that version blindly.
        val readyBatch = mutableListOf<LegacyVectorCandidate>()
        var skipped = 0
        var reused = 0
        for (candidate in batch) {
            val existingTarget = vectorStore.get(candidate.targetId)
            if (
                existingTarget == null ||
                (existingTarget.id == candidate.legacyId && existingTarget.metadata["embedding_model"] == null)
            ) {
                readyBatch += candidate
            } else if (
                existingTarget.metadata["embedding_model"] == embeddingModelId &&
                existingTarget.metadata["content"] == candidate.content
            ) {
                if (candidate.legacyId != candidate.targetId) vectorStore.delete(candidate.legacyId)
                reused++
            } else {
                skipped++
                Log.w(TAG, "Skipped legacy vector because its target ID now contains different content")
            }
        }
        if (readyBatch.isEmpty()) {
            return LegacyBatchResult(reindexed = 0, skipped = skipped, reused = reused)
        }

        val embeddings = embeddingService.embedBatch(
            readyBatch.map { it.content },
            "EMBEDDING_LEGACY_REINDEX",
        )
        check(embeddings.size == readyBatch.size) {
            "Legacy vector reindex count mismatch: expected ${readyBatch.size}, received ${embeddings.size}"
        }
        val expectedDimension = embeddingService.getDimension()
        val actualDimensions = embeddings.map { it.size }.distinct()
        check(
            actualDimensions.size <= 1 &&
                (expectedDimension <= 0 || actualDimensions.singleOrNull() == expectedDimension),
        ) {
            "Legacy vector reindex returned unexpected dimensions: $actualDimensions (expected $expectedDimension)"
        }
        vectorStore.addBatch(
            readyBatch.mapIndexed { index, candidate ->
                VectorStore.VectorEntry(candidate.targetId, embeddings[index], candidate.metadata)
            },
        )
        // Delete only after the replacement batch has been persisted.
        readyBatch.filter { it.legacyId != it.targetId }
            .forEach { vectorStore.delete(it.legacyId) }
        return LegacyBatchResult(
            reindexed = readyBatch.size,
            skipped = skipped,
            reused = reused,
        )
    }

    private fun generateVectorId(identity: VectorIdentity): String {
        val encodedIdentity =
            listOf(
                identity.ownerId,
                identity.characterId,
                identity.sessionId,
                identity.messageId,
                identity.contentType,
                identity.modelId,
            ).joinToString(separator = "\u0000")
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(encodedIdentity.toByteArray(StandardCharsets.UTF_8))
        return "vec_" + digest.joinToString("") { byte ->
            ("%0" + HEX_BYTE_WIDTH + "x").format(byte.toInt() and BYTE_MASK)
        }
    }

    private data class VectorIdentity(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val messageId: String,
        val contentType: String,
        val modelId: String,
    )

    private data class LegacyVectorCandidate(
        val legacyId: String,
        val targetId: String,
        val content: String,
        val metadata: Map<String, Any>,
    )

    private data class LegacyCandidatePreparation(
        val candidates: List<LegacyVectorCandidate>,
        val skipped: Int,
        val reused: Int,
    )

    private data class LegacyBatchResult(
        val reindexed: Int,
        val skipped: Int,
        val reused: Int,
    )

    data class LegacyReindexResult(
        val scannedLegacy: Int,
        val migrated: Int,
        val skipped: Int,
        val reused: Int,
    )

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
            recordVectorStorageFailure(_storageError, e)
            Log.e(TAG, "Failed to get statistics (${e.javaClass.simpleName})")
            VectorStatistics(0, 0)
        }
    }

    /**
     * 向量统计信息
     */
    data class VectorStatistics(
        val totalVectors: Int,
        val dimension: Int,
    )
}
