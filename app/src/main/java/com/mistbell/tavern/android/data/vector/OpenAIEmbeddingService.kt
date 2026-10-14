package com.mistbell.tavern.android.data.vector

import android.util.Log
import com.mistbell.tavern.android.data.api.ApiCallTrace
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Embedding 服务实现
 *
 * 移植自后端 OpenAIEmbeddingService.java
 *
 * 使用可配置的 OpenAI 兼容 Embeddings 模型生成向量
 */
@Suppress("TooManyFunctions")
class OpenAIEmbeddingService(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "text-embedding-3-small",
) : EmbeddingService {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    companion object {
        private const val TAG = "OpenAIEmbedding"
        private const val MAX_RETRIES = 3
        private const val EMBEDDING_CACHE_SIZE = 512
        private const val CACHE_LOAD_FACTOR = 0.75f
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val ENDPOINT_FINGERPRINT_BYTES = 8
        private const val ENDPOINT_FINGERPRINT_HEX_LENGTH = ENDPOINT_FINGERPRINT_BYTES * 2
        private const val BYTE_MASK = 0xff
    }

    private val cacheLock = Any()
    private val embeddingCache =
        object : LinkedHashMap<String, FloatArray>(EMBEDDING_CACHE_SIZE + 1, CACHE_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
                size > EMBEDDING_CACHE_SIZE
        }
    @Volatile
    private var lastKnownDimension = 0

    private fun cacheKey(text: String): String {
        val input = "${modelId()}\u0000$text".toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
    }

    private fun cachedEmbedding(text: String): FloatArray? =
        synchronized(cacheLock) { embeddingCache[cacheKey(text)]?.clone() }

    private fun rememberEmbedding(text: String, embedding: FloatArray) {
        synchronized(cacheLock) { embeddingCache[cacheKey(text)] = embedding.clone() }
    }

    override suspend fun embed(text: String): FloatArray =
        embed(text, "EMBEDDING_UNSPECIFIED")

    @Suppress("TooGenericExceptionCaught")
    override suspend fun embed(
        text: String,
        source: String,
    ): FloatArray =
        withContext(Dispatchers.IO) {
            val traceId = ApiCallTrace.newId()
            val startedAt = System.currentTimeMillis()
            cachedEmbedding(text)?.let { cached ->
                ApiCallTrace.log(
                    traceId,
                    source,
                    "CACHE_HIT",
                    "engine=remote model=$model dimension=${cached.size}",
                )
                return@withContext cached
            }
            ApiCallTrace.log(
                traceId,
                source,
                "START",
                "engine=remote model=$model " +
                    "endpoint=${ApiCallTrace.safeEndpoint("$normalizedBaseUrl/embeddings")} operation=single",
            )
            try {
                val embedding = embedUncached(text)
                rememberEmbedding(text, embedding)
                lastKnownDimension = embedding.size
                ApiCallTrace.log(
                    traceId,
                    source,
                    "SUCCESS",
                    "engine=remote model=$model dimension=${embedding.size} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt}",
                )
                embedding.clone()
            } catch (e: CancellationException) {
                ApiCallTrace.log(traceId, source, "CANCELLED", "engine=remote model=$model")
                throw e
            } catch (e: Exception) {
                ApiCallTrace.log(
                    traceId,
                    source,
                    "FAILURE",
                    "engine=remote model=$model " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt} error=${e.javaClass.simpleName}",
                )
                throw e
            }
        }

    private suspend fun embedUncached(text: String): FloatArray =
        withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            repeat(MAX_RETRIES) { attempt ->
                try {
                    return@withContext executeEmbedRequest(text)
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
                        Log.w(
                            TAG,
                            "Network error, retry ${attempt + 1}/$MAX_RETRIES " +
                                "after ${delayMs}ms (${e.javaClass.simpleName})",
                        )
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
                .url("$normalizedBaseUrl/embeddings")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val statusCode = response.code
            response.close()
            // Do not log untrusted gateway error bodies: some relays echo request headers or credentials.
            error("Embedding API returned HTTP $statusCode")
        }

        val responseBody =
            response.body?.string()
                ?: run {
                    response.close()
                    error("Empty response body")
                }

        val embeddingResponse = json.decodeFromString<EmbeddingResponse>(responseBody)

        if (embeddingResponse.data.isEmpty()) {
            throw Exception("No embedding data returned")
        }

        val embedding = embeddingResponse.data.first().embedding

        Log.d(TAG, "Generated embedding: dimension=${embedding.size}")

        return FloatArray(embedding.size) { i -> embedding[i].toFloat() }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
        embedBatch(texts, "EMBEDDING_BATCH_UNSPECIFIED")

    @Suppress("TooGenericExceptionCaught", "LongMethod")

    override suspend fun embedBatch(
        texts: List<String>,
        source: String,
    ): List<FloatArray> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()

            val traceId = ApiCallTrace.newId()
            val startedAt = System.currentTimeMillis()
            val results = arrayOfNulls<FloatArray>(texts.size)
            val missingIndicesByText = linkedMapOf<String, MutableList<Int>>()
            texts.forEachIndexed { index, text ->
                val cached = cachedEmbedding(text)
                if (cached == null) {
                    missingIndicesByText.getOrPut(text) { mutableListOf() }.add(index)
                } else {
                    results[index] = cached
                }
            }

            val cacheHits = results.count { it != null }
            if (missingIndicesByText.isEmpty()) {
                ApiCallTrace.log(
                    traceId,
                    source,
                    "CACHE_HIT",
                    "engine=remote model=$model operation=batch itemCount=${texts.size}",
                )
                return@withContext results.map { it!!.clone() }
            }

            ApiCallTrace.log(
                traceId,
                source,
                "START",
                "engine=remote model=$model operation=batch itemCount=${texts.size} " +
                    "cacheHits=$cacheHits cacheMisses=${missingIndicesByText.size}",
            )
            try {
                val missingTexts = missingIndicesByText.keys.toList()
                val generated = embedBatchUncached(missingTexts)
                check(generated.size == missingTexts.size) {
                    "Embedding batch response count mismatch"
                }
                missingTexts.forEachIndexed { index, text ->
                    val embedding = generated[index]
                    rememberEmbedding(text, embedding)
                    missingIndicesByText.getValue(text).forEach { resultIndex ->
                        results[resultIndex] = embedding.clone()
                    }
                }
                results.firstOrNull { it != null }?.let { lastKnownDimension = it.size }
                val dimension = results.firstOrNull { it != null }?.size ?: 0
                ApiCallTrace.log(
                    traceId,
                    source,
                    "SUCCESS",
                    "engine=remote model=$model itemCount=${texts.size} " +
                        "cacheHits=$cacheHits dimension=$dimension " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt}",
                )
                results.map { it?.clone() ?: error("Missing embedding result") }
            } catch (e: CancellationException) {
                ApiCallTrace.log(traceId, source, "CANCELLED", "engine=remote model=$model operation=batch")
                throw e
            } catch (e: Exception) {
                ApiCallTrace.log(
                    traceId,
                    source,
                    "FAILURE",
                    "engine=remote model=$model operation=batch " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt} error=${e.javaClass.simpleName}",
                )
                throw e
            }
        }

    private suspend fun embedBatchUncached(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            repeat(MAX_RETRIES) { attempt ->
                try {
                    return@withContext executeEmbedBatchRequest(texts)
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
                        Log.w(
                            TAG,
                            "Network error, retry ${attempt + 1}/$MAX_RETRIES " +
                                "after ${delayMs}ms (${e.javaClass.simpleName})",
                        )
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
                .url("$normalizedBaseUrl/embeddings")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val statusCode = response.code
            response.close()
            // Do not log untrusted gateway error bodies: some relays echo request headers or credentials.
            error("Embedding API returned HTTP $statusCode")
        }

        val responseBody =
            response.body?.string()
                ?: run {
                    response.close()
                    error("Empty response body")
                }

        val embeddingResponse = json.decodeFromString<EmbeddingResponse>(responseBody)

        if (embeddingResponse.data.isEmpty()) {
            throw Exception("No embedding data returned")
        }

        Log.d(TAG, "Generated ${embeddingResponse.data.size} embeddings in batch")

        return embeddingResponse.data.sortedBy { it.index }.map { data ->
            FloatArray(data.embedding.size) { i -> data.embedding[i].toFloat() }
        }
    }

    override fun modelId(): String {
        val endpointFingerprint =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(normalizedBaseUrl.toByteArray())
                .take(ENDPOINT_FINGERPRINT_BYTES)
                .joinToString("") {
                    ("%0" + ENDPOINT_FINGERPRINT_HEX_LENGTH + "x").format(it.toInt() and BYTE_MASK)
                }
        return "openai-compatible:$model:$endpointFingerprint"
    }

    override fun supportsLegacyVectors(): Boolean =
        model == "text-embedding-3-small" && normalizedBaseUrl == "https://api.openai.com/v1"

    override fun getDimension(): Int = lastKnownDimension

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
        val index: Int = 0,
    )
}
