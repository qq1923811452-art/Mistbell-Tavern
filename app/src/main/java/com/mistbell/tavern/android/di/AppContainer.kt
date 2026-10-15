package com.mistbell.tavern.android.di

import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.vector.CachedVectorStore
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.LocalEmbeddingService
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.OpenAIEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.util.SecureStore

/**
 * 应用级 DI 容器：集中装配 database / vectorStore / embeddingService / vectorMemoryService。
 *
 * 各服务均延迟初始化；装配失败按原语义回退（向量服务回退内存实现，embedding 回退 Mock），
 * 绝不因装配崩溃阻塞应用启动。
 */
@Suppress("TooGenericExceptionCaught")
class AppContainer(private val appContext: Context) {
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(appContext)
    }

    val vectorStore: VectorStore by lazy {
        try {
            val baseStore = InMemoryVectorStore(appContext)
            CachedVectorStore(baseStore, cacheSize = 50).also {
                Log.d(TAG, "Vector store initialized with cache (lazy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vector store: ${e.message}", e)
            val baseStore = InMemoryVectorStore(appContext)
            CachedVectorStore(baseStore, cacheSize = 50)
        }
    }

    /**
     * Embedding 服务（延迟初始化，源路由：settings `embedding_source`）
     *
     * - auto（默认）：有 embedding API key → OpenAI；无 key → Mock（词法回退）——兼容历史行为；
     * - api：强制 API（无 key 则 Mock）；
     * - local：本地 ONNX（bge-small-zh）；初始化失败回退 Mock 并记日志。
     */
    private val embeddingLock = Any()

    private data class EmbeddingRuntime(
        val embeddingService: EmbeddingService,
        val vectorMemoryService: VectorMemoryService,
    )

    @Volatile private var currentEmbeddingRuntime: EmbeddingRuntime? = null

    /** Resolve lazily and publish the embedding/vector service pair atomically. */
    private fun getOrCreateEmbeddingRuntime(): EmbeddingRuntime =
        currentEmbeddingRuntime ?: synchronized(embeddingLock) {
            currentEmbeddingRuntime ?: kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val service = assembleEmbeddingService()
                EmbeddingRuntime(
                    embeddingService = service,
                    vectorMemoryService = VectorMemoryService(vectorStore, service),
                ).also { currentEmbeddingRuntime = it }
            }
        }

    val embeddingService: EmbeddingService
        get() = getOrCreateEmbeddingRuntime().embeddingService

    private suspend fun assembleEmbeddingService(): EmbeddingService {
        val source =
            runCatching { database.settingsDao().getValue("embedding_source") }.getOrNull()
        return try {
            when (source) {
                "local" -> {
                    Log.d(TAG, "Embedding source=local: loading bge-small-zh (ONNX, lazy)")
                    runCatching { LocalEmbeddingService.create(appContext) }
                        .onFailure { Log.e(TAG, "Local embedding init failed, fallback to mock", it) }
                        .getOrElse { MockEmbeddingService() }
                }
                "api" -> openAiEmbeddingOrMock()
                else ->
                    if (getEmbeddingApiKey().isNotBlank()) {
                        openAiEmbeddingOrMock()
                    } else {
                        MockEmbeddingService()
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding service: ${e.message}", e)
            MockEmbeddingService()
        }
    }

    private fun openAiEmbeddingOrMock(): EmbeddingService {
        val apiKey = getEmbeddingApiKey()
        val baseUrl = getEmbeddingBaseUrl()
        return if (apiKey.isNotBlank()) {
            Log.d(TAG, "Using OpenAI Embedding Service (lazy)")
            OpenAIEmbeddingService(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = "text-embedding-3-small",
            )
        } else {
            Log.d(TAG, "No embedding API key, vector memory unavailable (lexical fallback)")
            MockEmbeddingService()
        }
    }

    val vectorMemoryService: VectorMemoryService
        get() = getOrCreateEmbeddingRuntime().vectorMemoryService

    /** Refresh both services as one atomic runtime after embedding settings change. */
    suspend fun refreshEmbeddingServices() {
        val newEmbeddingService = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assembleEmbeddingService()
        }
        val newRuntime = EmbeddingRuntime(
            embeddingService = newEmbeddingService,
            vectorMemoryService = VectorMemoryService(vectorStore, newEmbeddingService),
        )
        // Publish the new provider pair first, then invalidate results from the previous runtime.
        // Model identity is also part of retrieval filters, so old in-flight searches cannot match
        // a different model namespace after this swap.
        synchronized(embeddingLock) {
            currentEmbeddingRuntime = newRuntime
        }
        (vectorStore as? CachedVectorStore)?.invalidateSearchCache()
    }

    private fun getEmbeddingApiKey(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SecureStore.unwrap(prefs.getString(KEY_EMBEDDING_API_KEY, "") ?: "")
    }

    private fun getEmbeddingBaseUrl(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EMBEDDING_BASE_URL, "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"
    }

    companion object {
        private const val TAG = "AppContainer"
        private const val PREFS_NAME = "tavern_settings"
        private const val KEY_EMBEDDING_API_KEY = "embedding_api_key"
        private const val KEY_EMBEDDING_BASE_URL = "embedding_base_url"
    }
}
