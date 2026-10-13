package com.mistbell.tavern.android.data.vector

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * OpenAI Embedding 服务实现
 *
 * 移植自后端 OpenAIEmbeddingService.java
 *
 * 使用 OpenAI 的 text-embedding-3-small 模型生成向量
 */
class OpenAIEmbeddingService(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "text-embedding-3-small",
) : EmbeddingService {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    // 有界内存 LRU：复用重复文本的 embedding，避免重复网络请求。
    // 只缓存进程内数据；键使用 SHA-256，避免把完整聊天原文作为缓存键长期保留。
    private val cacheLock = Any()
    private val embeddingCache =
        object : LinkedHashMap<String, FloatArray>(EMBEDDING_CACHE_SIZE + 1, CACHE_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
                size > EMBEDDING_CACHE_SIZE
        }

    private fun cacheKey(text: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun cachedEmbedding(text: String): FloatArray? {
        return synchronized(cacheLock) {
            embeddingCache[cacheKey(text)]?.clone()
        }
    }

    private fun rememberEmbedding(text: String, embedding: FloatArray) {
        synchronized(cacheLock) {
            embeddingCache[cacheKey(text)] = embedding.clone()
        }
    }

    companion object {
        private const val TAG = "OpenAIEmbedding"
        private const val DIMENSION = 1536
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val EMBEDDING_CACHE_SIZE = 512
        private const val CACHE_LOAD_FACTOR = 0.75f
    }

    override suspend fun embed(text: String): FloatArray =
        withContext(Dispatchers.IO) {
            cachedEmbedding(text)?.let { return@withContext it }

            var lastException: Exception? = null

            repeat(MAX_RETRIES) { attempt ->
                try {
                    val embedding = executeEmbedRequest(text)
                    rememberEmbedding(text, embedding)
                    return@withContext embedding.clone()
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    if (attempt < MAX_RETRIES - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                        Log.w(TAG, "Embedding timeout, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
                        delay(delayMs)
                    }
                } catch (e: IOException) {
                    lastException = e
                    if (attempt < MAX_RETRIES - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                        Log.w(TAG, "Network error, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("429") == true) {
                        lastException = e
                        if (attempt < MAX_RETRIES - 1) {
                            val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) * 2
                            Log.w(TAG, "Rate limit hit, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
                            delay(delayMs)
                        }
                    } else {
                        throw e
                    }
                }
            }

            throw lastException ?: Exception("Embedding request failed after $MAX_RETRIES retries")
        }

    private fun executeEmbedRequest(text: String): FloatArray {
        val requestBody =
            EmbeddingRequestSingle(
                input = text,
                model = model,
            )

        val jsonBody =
            json.encodeToString(
                EmbeddingRequestSingle.serializer(),
                requestBody,
            )

        val request =
            Request.Builder()
                .url("$baseUrl/embeddings")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("OpenAI API error: ${response.code} - $errorBody")
        }

        val responseBody =
            response.body?.string()
                ?: throw Exception("Empty response body")

        val embeddingResponse = json.decodeFromString<EmbeddingResponse>(responseBody)

        if (embeddingResponse.data.isEmpty()) {
            throw Exception("No embedding data returned")
        }

        val embedding = embeddingResponse.data.first().embedding

        Log.d(TAG, "Generated embedding: dimension=${embedding.size}")

        return FloatArray(embedding.size) { i -> embedding[i].toFloat() }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()

            val uniqueTexts = texts.distinct()
            val missingTexts = uniqueTexts.filter { cachedEmbedding(it) == null }
            if (missingTexts.isNotEmpty()) {
                val embeddings = requestEmbedBatchWithRetry(missingTexts)
                if (embeddings.size != missingTexts.size) {
                    throw IOException(
                        "Embedding batch size mismatch: expected ${missingTexts.size}, got ${embeddings.size}",
                    )
                }
                missingTexts.forEachIndexed { index, text ->
                    rememberEmbedding(text, embeddings[index])
                }
            }

            texts.map { text ->
                cachedEmbedding(text)
                    ?: throw IOException("Embedding cache missing result after batch request")
            }
        }

    @Suppress("NestedBlockDepth")
    private suspend fun requestEmbedBatchWithRetry(texts: List<String>): List<FloatArray> {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return executeEmbedBatchRequest(texts)
            } catch (e: SocketTimeoutException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                    Log.w(TAG, "Batch embedding timeout, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
                    delay(delayMs)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                    Log.w(TAG, "Network error, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    lastException = e
                    if (attempt < MAX_RETRIES - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) * 2
                        Log.w(TAG, "Rate limit hit, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms")
                        delay(delayMs)
                    }
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: Exception("Batch embedding request failed after $MAX_RETRIES retries")
    }

    private fun executeEmbedBatchRequest(texts: List<String>): List<FloatArray> {
        val requestBody =
            EmbeddingRequestBatch(
                input = texts,
                model = model,
            )

        val jsonBody =
            json.encodeToString(
                EmbeddingRequestBatch.serializer(),
                requestBody,
            )

        val request =
            Request.Builder()
                .url("$baseUrl/embeddings")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("OpenAI API error: ${response.code} - $errorBody")
        }

        val responseBody =
            response.body?.string()
                ?: throw Exception("Empty response body")

        val embeddingResponse = json.decodeFromString<EmbeddingResponse>(responseBody)

        if (embeddingResponse.data.isEmpty()) {
            throw Exception("No embedding data returned")
        }

        Log.d(TAG, "Generated ${embeddingResponse.data.size} embeddings in batch")

        return embeddingResponse.data.map { data ->
            FloatArray(data.embedding.size) { i -> data.embedding[i].toFloat() }
        }
    }

    override fun getDimension(): Int = DIMENSION

    @Serializable
    private data class EmbeddingRequestSingle(
        val input: String,
        val model: String,
    )

    @Serializable
    private data class EmbeddingRequestBatch(
        val input: List<String>,
        val model: String,
    )

    @Serializable
    private data class EmbeddingResponse(
        val data: List<EmbeddingData>,
    )

    @Serializable
    private data class EmbeddingData(
        val embedding: List<Double>,
        val index: Int,
    )
}
