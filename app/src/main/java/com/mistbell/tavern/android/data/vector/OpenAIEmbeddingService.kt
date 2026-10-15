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

    companion object {
        private const val TAG = "OpenAIEmbedding"
        private const val DIMENSION = 1536
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    override suspend fun embed(text: String): FloatArray =
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
                        Log.w(TAG, "Network error, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    }
                } catch (e: Exception) {
                    if (isRetryableHttpError(e)) {
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

            lastException?.let { throw it } ?: error("Embedding request failed after $MAX_RETRIES retries")
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
            throw EmbeddingHttpException(response.code, "OpenAI API error: ${response.code} - $errorBody")
        }

        val responseBody =
            response.body?.string()
                ?: error("Empty response body")

        val embeddingResponse = json.decodeFromString<EmbeddingResponse>(responseBody)

        if (embeddingResponse.data.isEmpty()) {
            error("No embedding data returned")
        }

        val embedding = embeddingResponse.data.first().embedding
        if (embedding.size != DIMENSION) {
            error(
                "Unexpected embedding dimension for model $model: ${embedding.size} (expected $DIMENSION)",
            )
        }

        Log.d(TAG, "Generated embedding: dimension=${embedding.size}")

        return FloatArray(embedding.size) { i -> embedding[i].toFloat() }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
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
                        Log.w(TAG, "Network error, retry ${attempt + 1}/$MAX_RETRIES after ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    }
                } catch (e: Exception) {
                    if (isRetryableHttpError(e)) {
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

            lastException?.let { throw it } ?: error("Batch embedding request failed after $MAX_RETRIES retries")
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
            throw EmbeddingHttpException(response.code, "OpenAI API error: ${response.code} - $errorBody")
        }

        val responseBody =
            response.body?.string()
                ?: error("Empty response body")

        val embeddingResponse = json.decodeFromString<EmbeddingResponse>(responseBody)

        if (embeddingResponse.data.isEmpty()) {
            error("No embedding data returned")
        }

        Log.d(TAG, "Generated ${embeddingResponse.data.size} embeddings in batch")

        validateEmbeddingResponseIndices(
            indices = embeddingResponse.data.map { it.index },
            expectedCount = texts.size,
        )
        val orderedData = embeddingResponse.data.sortedBy { it.index }
        if (orderedData.any { it.embedding.size != DIMENSION }) {
            val dimensions = orderedData.map { it.embedding.size }.distinct()
            error(
                "Unexpected embedding dimensions for model $model: $dimensions (expected $DIMENSION)",
            )
        }

        return orderedData.map { data ->
            FloatArray(data.embedding.size) { i -> data.embedding[i].toFloat() }
        }
    }

    private fun isRetryableHttpError(error: Exception): Boolean =
        error is EmbeddingHttpException && isRetryableEmbeddingStatusCode(error.statusCode)

    private class EmbeddingHttpException(
        val statusCode: Int,
        message: String,
    ) : Exception(message)

    override fun getDimension(): Int = DIMENSION

    override fun getModelId(): String = "api:${baseUrl.trimEnd('/')}:$model"

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

/** Reject duplicate, missing, negative, or out-of-range indexes before trusting batch order. */
internal fun validateEmbeddingResponseIndices(
    indices: List<Int>,
    expectedCount: Int,
) {
    if (indices.size != expectedCount) {
        error("Embedding response count mismatch: expected $expectedCount, received ${indices.size}")
    }
    if (indices.distinct().size != indices.size) {
        error("Embedding response contains duplicate input indices")
    }
    val expectedIndices = (0 until expectedCount).toList()
    if (indices.sorted() != expectedIndices) {
        error(
            "Embedding response contains invalid input indices: expected $expectedIndices, received ${indices.sorted()}",
        )
    }
}

internal fun isRetryableEmbeddingStatusCode(statusCode: Int): Boolean =
    statusCode == 429 || statusCode in 500..599
