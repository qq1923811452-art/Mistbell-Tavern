package com.mistbell.tavern.android.data.vector

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import com.mistbell.tavern.android.data.api.ApiCallTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * EmbeddingGemma 2 text-only model running on-device through LiteRT-LM.
 *
 * The approximately 165 MB model is downloaded on first activation into app-private storage;
 * after that, inference is offline. GPU is attempted first, with CPU fallback if GPU
 * initialization is unsupported on the device. This service never falls back to a remote API.
 */
class EmbeddingGemma2LocalEmbeddingService private constructor(
    private val engine: EmbeddingEngine,
) : EmbeddingService, AutoCloseable {
    companion object {
        private const val TAG = "EmbeddingGemma2Local"
        private const val MODEL_DIR = "litert-models"
        private const val MODEL_FILE_NAME = "embeddinggemma-2-text-270m.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b/embeddinggemma-2-text-270m.litertlm?download=true"
        private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
        private const val MAX_MODEL_BYTES = 250L * 1024L * 1024L
        private const val EXPECTED_DIMENSION = 768
        private const val QUERY_PREFIX = "task: search query | text: "
        private const val DOCUMENT_PREFIX = "task: search result | text: "

        private val httpClient =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.MINUTES)
                .build()

        suspend fun create(context: Context): EmbeddingGemma2LocalEmbeddingService =
            withContext(Dispatchers.IO) {
                val modelFile = ensureModelDownloaded(context)
                createEngine(modelFile.absolutePath)
            }

        private suspend fun ensureModelDownloaded(context: Context): File {
            val directory = File(context.filesDir, MODEL_DIR)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create private model directory")
            }
            val modelFile = File(directory, MODEL_FILE_NAME)
            if (modelFile.isFile && modelFile.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                return modelFile
            }
            if (modelFile.exists() && !modelFile.delete()) {
                throw IOException("Existing EmbeddingGemma model file is invalid and cannot be removed")
            }

            val temporaryFile = File(directory, "$MODEL_FILE_NAME.part")
            temporaryFile.delete()
            val request = Request.Builder().url(MODEL_URL).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("EmbeddingGemma model download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Model download returned an empty body")
                val advertisedLength = body.contentLength()
                if (advertisedLength > MAX_MODEL_BYTES) {
                    throw IOException("Model download exceeded the allowed size")
                }
                var copiedBytes = 0L
                val coroutineContext = currentCoroutineContext()
                body.byteStream().use { input ->
                    FileOutputStream(temporaryFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copiedBytes += count
                            if (copiedBytes > MAX_MODEL_BYTES) {
                                throw IOException("Model download exceeded the allowed size")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (copiedBytes < MIN_MODEL_BYTES || (advertisedLength > 0 && copiedBytes != advertisedLength)) {
                    throw IOException("Downloaded model size is invalid; partial file will not be activated")
                }
            } catch (e: Exception) {
                temporaryFile.delete()
                throw e
            }

            if (!temporaryFile.renameTo(modelFile)) {
                temporaryFile.delete()
                throw IOException("Unable to atomically activate downloaded EmbeddingGemma model")
            }
            return modelFile
        }

        private fun createEngine(modelPath: String): EmbeddingGemma2LocalEmbeddingService {
            var gpuFailure: Exception? = null
            val gpuEngine = EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = Backend.GPU()))
            try {
                gpuEngine.initialize()
                Log.i(TAG, "EmbeddingGemma 2 initialized with GPU backend")
                return EmbeddingGemma2LocalEmbeddingService(gpuEngine)
            } catch (e: Exception) {
                gpuFailure = e
                runCatching { gpuEngine.close() }
                Log.w(TAG, "GPU backend unavailable; retrying EmbeddingGemma 2 on CPU", e)
            }

            val cpuEngine = EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = Backend.CPU()))
            try {
                cpuEngine.initialize()
                Log.i(TAG, "EmbeddingGemma 2 initialized with CPU backend")
                return EmbeddingGemma2LocalEmbeddingService(cpuEngine)
            } catch (e: Exception) {
                runCatching { cpuEngine.close() }
                throw IOException("EmbeddingGemma 2 could not initialize on GPU or CPU", e).also {
                    gpuFailure?.let(it::addSuppressed)
                }
            }
        }
    }

    private val inferenceMutex = Mutex()

    override fun modelId(): String = "local:embeddinggemma-2-text-270m-litertlm:$EXPECTED_DIMENSION"

    override fun supportsLegacyVectors(): Boolean = false

    override fun getDimension(): Int = EXPECTED_DIMENSION

    override suspend fun embed(text: String): FloatArray = embed(text, "LOCAL_GEMMA_UNSPECIFIED")

    override suspend fun embed(text: String, source: String): FloatArray =
        withContext(Dispatchers.Default) {
            val traceId = ApiCallTrace.newId()
            val startedAt = System.currentTimeMillis()
            ApiCallTrace.log(traceId, source, "START", "engine=LiteRT-LM model=EmbeddingGemma2 network=false")
            try {
                val normalizedText = text.trim()
                val prefix = if (source.contains("MEMORY_QUERY")) QUERY_PREFIX else DOCUMENT_PREFIX
                val input = InputData.Text(prefix + normalizedText)
                val vector = inferenceMutex.withLock {
                    val embedding = engine.computeEmbedding(listOf(input)).embedding
                    FloatArray(embedding.size) { embedding[it].toFloat() }
                }
                require(vector.isNotEmpty() && vector.all(Float::isFinite)) {
                    "EmbeddingGemma 2 returned an empty or non-finite vector"
                }
                require(vector.size == EXPECTED_DIMENSION) {
                    "EmbeddingGemma 2 returned ${vector.size} dimensions; expected $EXPECTED_DIMENSION"
                }
                val normalized = normalize(vector)
                ApiCallTrace.log(
                    traceId,
                    source,
                    "SUCCESS",
                    "engine=LiteRT-LM model=EmbeddingGemma2 backend=local dimension=${normalized.size} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt}",
                )
                normalized
            } catch (e: CancellationException) {
                ApiCallTrace.log(traceId, source, "CANCELLED", "engine=LiteRT-LM model=EmbeddingGemma2")
                throw e
            } catch (e: Exception) {
                ApiCallTrace.log(
                    traceId,
                    source,
                    "FAILURE",
                    "engine=LiteRT-LM model=EmbeddingGemma2 error=${e.javaClass.simpleName}",
                )
                throw e
            }
        }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
        texts.map { embed(it, "LOCAL_GEMMA_BATCH") }

    override suspend fun embedBatch(texts: List<String>, source: String): List<FloatArray> =
        texts.map { embed(it, source) }

    override fun close() {
        engine.close()
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var norm = 0.0
        vector.forEach { value -> norm += value.toDouble() * value }
        if (norm <= 0.0) return vector
        val magnitude = sqrt(norm)
        return FloatArray(vector.size) { index -> (vector[index] / magnitude).toFloat() }
    }
}
