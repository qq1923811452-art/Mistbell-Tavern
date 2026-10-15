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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * EmbeddingGemma 2 text-only model running on-device through LiteRT-LM.
 *
 * The Snapdragon 8 Gen 3 (SM8650) Qualcomm-optimized model is downloaded on first activation
 * into app-private storage; after that, inference is offline. Try Qualcomm NPU first, then GPU,
 * then CPU. This service never falls back to a remote API.
 */
@Suppress("TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
class EmbeddingGemma2LocalEmbeddingService private constructor(
    private val engine: EmbeddingEngine,
    private val backendName: String,
    private val modelVariant: String,
) : EmbeddingService, AutoCloseable {
    override val isSemantic: Boolean = true

    private val _runtimeStatus =
        MutableStateFlow(
            EmbeddingRuntimeStatus(
                source = "local-gemma",
                backend = backendName,
                modelVariant = modelVariant,
                initialized = true,
            ),
        )
    val runtimeStatus: StateFlow<EmbeddingRuntimeStatus> = _runtimeStatus.asStateFlow()

    companion object {
        private const val TAG = "EmbeddingGemma2Local"
        private const val MODEL_DIR = "litert-models"
        private const val MODEL_FILE_NAME = "embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/" +
                "resolve/e8ae63263a69e01014bb0420c0d48d03497041a2/" +
                "embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm?download=true"
        private const val MIN_MODEL_BYTES = 180L * 1024L * 1024L
        private const val MAX_MODEL_BYTES = 240L * 1024L * 1024L
        private const val EXPECTED_DIMENSION = 768
        private const val EXPECTED_MODEL_SHA256 = "6a5043146940e738cfa9111aa445b1cdb3dc7bbef30caef367622f14ada1a895"
        private const val COMPAT_MODEL_FILE_NAME = "embeddinggemma-2-text-270m.litertlm"
        private const val COMPAT_MODEL_URL =
            "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/" +
                "resolve/8c9f652/embeddinggemma-2-text-270m.litertlm?download=true"
        private const val COMPAT_MIN_MODEL_BYTES = 150L * 1024L * 1024L
        private const val COMPAT_MAX_MODEL_BYTES = 180L * 1024L * 1024L
        private const val COMPAT_MODEL_SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
        private const val BYTE_MASK = 0xff

        private val modelDownloadMutex = Mutex()

        @Volatile
        private var verifiedModelFingerprint: String? = null

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
                val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
                val missingNpuLibraries = QualcommNpuRuntimeAvailability.missingLibraries(nativeLibraryDir)
                if (missingNpuLibraries.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "Compatible Qualcomm NPU runtime is absent; skipping the 208 MB SM8650 model " +
                            "and using the 165 MB portable model. Missing: ${missingNpuLibraries.joinToString()}",
                    )
                    val compatibleModel = ensureCompatibleModelDownloaded(context)
                    return@withContext createCompatibleEngine(compatibleModel.absolutePath)
                }

                try {
                    val modelFile = ensureModelDownloaded(context)
                    createEngine(modelFile.absolutePath, context.applicationInfo.nativeLibraryDir)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (qualcommModelFailure: Exception) {
                    Log.w(
                        TAG,
                        "SM8650-optimized model could not initialize; trying the generic 165 MB EmbeddingGemma 2 model",
                        qualcommModelFailure,
                    )
                    val compatibleModel = ensureCompatibleModelDownloaded(context)
                    try {
                        createCompatibleEngine(compatibleModel.absolutePath)
                    } catch (compatibleModelFailure: Exception) {
                        compatibleModelFailure.addSuppressed(qualcommModelFailure)
                        throw compatibleModelFailure
                    }
                }
            }

        private suspend fun ensureCompatibleModelDownloaded(context: Context): File =
            modelDownloadMutex.withLock {
                val directory = File(context.filesDir, MODEL_DIR)
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Unable to create private compatibility-model directory")
                }
                val modelFile = File(directory, COMPAT_MODEL_FILE_NAME)
                if (modelFile.isFile &&
                    modelFile.length() in COMPAT_MIN_MODEL_BYTES..COMPAT_MAX_MODEL_BYTES &&
                    sha256(modelFile) == COMPAT_MODEL_SHA256
                ) {
                    return@withLock modelFile
                }
                if (modelFile.exists() && !modelFile.delete()) {
                    throw IOException("Invalid compatibility model cannot be removed")
                }
                val temporaryFile = File(directory, "$COMPAT_MODEL_FILE_NAME.part")
                temporaryFile.delete()
                val call = httpClient.newCall(Request.Builder().url(COMPAT_MODEL_URL).get().build())
                val coroutineContext = currentCoroutineContext()
                val cancellationHandle =
                    coroutineContext.job.invokeOnCompletion { cause ->
                        if (cause is CancellationException) call.cancel()
                    }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Compatibility model download failed: HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Compatibility model download returned no body")
                        val advertisedLength = body.contentLength()
                        if (advertisedLength > COMPAT_MAX_MODEL_BYTES) {
                            throw IOException("Compatibility model download exceeded size limit")
                        }
                        var copiedBytes = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(temporaryFile).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copiedBytes += count
                                    if (copiedBytes > COMPAT_MAX_MODEL_BYTES) {
                                        throw IOException("Compatibility model download exceeded size limit")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        }
                        if (copiedBytes !in COMPAT_MIN_MODEL_BYTES..COMPAT_MAX_MODEL_BYTES ||
                            (advertisedLength > 0 && copiedBytes != advertisedLength)
                        ) {
                            throw IOException("Compatibility model download size is invalid")
                        }
                        if (sha256(temporaryFile) != COMPAT_MODEL_SHA256) {
                            throw IOException("Compatibility model failed SHA-256 verification")
                        }
                    }
                } catch (e: Exception) {
                    temporaryFile.delete()
                    coroutineContext.ensureActive()
                    throw e
                } finally {
                    cancellationHandle.dispose()
                }
                if (!temporaryFile.renameTo(modelFile)) {
                    temporaryFile.delete()
                    throw IOException("Unable to atomically activate compatibility model")
                }
                modelFile
            }

        private suspend fun ensureModelDownloaded(context: Context): File =
            modelDownloadMutex.withLock { ensureModelDownloadedLocked(context) }

        @Suppress("NestedBlockDepth", "ThrowsCount")
        private suspend fun ensureModelDownloadedLocked(context: Context): File {
            val directory = File(context.filesDir, MODEL_DIR)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create private model directory")
            }
            val modelFile = File(directory, MODEL_FILE_NAME)
            if (
                modelFile.isFile &&
                modelFile.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES &&
                isVerifiedModel(modelFile)
            ) {
                return modelFile
            }
            if (modelFile.exists() && !modelFile.delete()) {
                throw IOException("Existing EmbeddingGemma model file is invalid and cannot be removed")
            }

            val temporaryFile = File(directory, "$MODEL_FILE_NAME.part")
            temporaryFile.delete()
            val request =
                Request.Builder()
                    .url(MODEL_URL)
                    .get()
                    .build()
            val call = httpClient.newCall(request)
            val coroutineContext = currentCoroutineContext()
            val cancellationHandle =
                coroutineContext.job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) call.cancel()
                }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("EmbeddingGemma model download failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Model download returned an empty body")
                    val advertisedLength = body.contentLength()
                    if (advertisedLength > MAX_MODEL_BYTES) {
                        throw IOException("Model download exceeded the allowed size")
                    }
                    var copiedBytes = 0L
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
                    if (
                        copiedBytes < MIN_MODEL_BYTES ||
                        (advertisedLength > 0 && copiedBytes != advertisedLength)
                    ) {
                        throw IOException("Downloaded model size is invalid; partial file will not be activated")
                    }
                    if (sha256(temporaryFile) != EXPECTED_MODEL_SHA256) {
                        throw IOException("Downloaded EmbeddingGemma model failed SHA-256 verification")
                    }
                }
            } catch (e: Exception) {
                temporaryFile.delete()
                coroutineContext.ensureActive()
                throw e
            } finally {
                cancellationHandle.dispose()
            }

            if (!temporaryFile.renameTo(modelFile)) {
                temporaryFile.delete()
                throw IOException("Unable to atomically activate downloaded EmbeddingGemma model")
            }
            verifiedModelFingerprint = modelFingerprint(modelFile)
            return modelFile
        }

        @Suppress("ReturnCount")
        private suspend fun isVerifiedModel(file: File): Boolean {
            if (verifiedModelFingerprint == modelFingerprint(file)) return true
            if (sha256(file) != EXPECTED_MODEL_SHA256) return false
            verifiedModelFingerprint = modelFingerprint(file)
            return true
        }

        private fun modelFingerprint(file: File): String = "${file.length()}:${file.lastModified()}"

        private suspend fun sha256(file: File): String {
            val coroutineContext = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and BYTE_MASK)
            }
        }

        @Suppress("ReturnCount")
        private fun createEngine(
            modelPath: String,
            nativeLibraryDir: String,
        ): EmbeddingGemma2LocalEmbeddingService {
            var npuFailure: Throwable? = null
            val missingNpuLibraries =
                QualcommNpuRuntimeAvailability.missingLibraries(File(nativeLibraryDir))
            if (missingNpuLibraries.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Qualcomm NPU skipped: compatible dispatch/runtime libraries are missing " +
                        "(${missingNpuLibraries.joinToString()}); trying GPU",
                )
            } else {
                var npuEngine: EmbeddingEngine? = null
                try {
                    val candidate =
                        EmbeddingEngine(
                            EmbeddingEngineConfig(
                                modelPath = modelPath,
                                backend = Backend.NPU(nativeLibraryDir = nativeLibraryDir),
                            ),
                        )
                    npuEngine = candidate
                    candidate.initialize()
                    Log.i(TAG, "EmbeddingGemma 2 initialized with Qualcomm NPU backend (SM8650 model)")
                    return EmbeddingGemma2LocalEmbeddingService(candidate, "NPU", "sm8650")
                } catch (e: Exception) {
                    npuFailure = e
                    npuEngine?.let { runCatching { it.close() } }
                    Log.w(TAG, "Qualcomm NPU initialization failed; retrying on GPU", e)
                } catch (e: LinkageError) {
                    npuFailure = e
                    npuEngine?.let { runCatching { it.close() } }
                    Log.w(TAG, "Qualcomm NPU native runtime failed; retrying on GPU", e)
                }
            }

            var gpuFailure: Throwable? = null
            var gpuEngine: EmbeddingEngine? = null
            try {
                val candidate =
                    EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = Backend.GPU()))
                gpuEngine = candidate
                candidate.initialize()
                Log.i(TAG, "EmbeddingGemma 2 initialized with GPU backend")
                return EmbeddingGemma2LocalEmbeddingService(candidate, "GPU", "sm8650")
            } catch (e: Exception) {
                gpuFailure = e
                gpuEngine?.let { runCatching { it.close() } }
                Log.w(TAG, "GPU backend unavailable; retrying EmbeddingGemma 2 on CPU", e)
            } catch (e: LinkageError) {
                gpuFailure = e
                gpuEngine?.let { runCatching { it.close() } }
                Log.w(TAG, "GPU native backend unavailable; retrying EmbeddingGemma 2 on CPU", e)
            }

            var cpuEngine: EmbeddingEngine? = null
            try {
                val candidate =
                    EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = Backend.CPU()))
                cpuEngine = candidate
                candidate.initialize()
                Log.i(TAG, "EmbeddingGemma 2 initialized with CPU backend")
                return EmbeddingGemma2LocalEmbeddingService(candidate, "CPU", "sm8650")
            } catch (e: Exception) {
                cpuEngine?.let { runCatching { it.close() } }
                throw IOException("EmbeddingGemma 2 could not initialize on NPU, GPU or CPU", e).also {
                    npuFailure?.let(it::addSuppressed)
                    gpuFailure?.let(it::addSuppressed)
                }
            } catch (e: LinkageError) {
                cpuEngine?.let { runCatching { it.close() } }
                throw IOException("EmbeddingGemma 2 native runtime is unavailable", e).also {
                    npuFailure?.let(it::addSuppressed)
                    gpuFailure?.let(it::addSuppressed)
                }
            }
        }

        private fun createCompatibleEngine(modelPath: String): EmbeddingGemma2LocalEmbeddingService {
            var gpuFailure: Throwable? = null
            var gpuEngine: EmbeddingEngine? = null
            try {
                val candidate =
                    EmbeddingEngine(
                        EmbeddingEngineConfig(
                            modelPath = modelPath,
                            backend = Backend.GPU(),
                        ),
                    )
                gpuEngine = candidate
                candidate.initialize()
                Log.i(
                    TAG,
                    "Generic EmbeddingGemma 2 initialized with GPU backend (NPU intentionally skipped)",
                )
                return EmbeddingGemma2LocalEmbeddingService(candidate, "GPU", "generic-8c9f652")
            } catch (e: Exception) {
                gpuFailure = e
                gpuEngine?.let { runCatching { it.close() } }
                Log.w(TAG, "Generic EmbeddingGemma 2 GPU unavailable; retrying on CPU", e)
            } catch (e: LinkageError) {
                gpuFailure = e
                gpuEngine?.let { runCatching { it.close() } }
                Log.w(TAG, "Generic EmbeddingGemma 2 GPU native runtime unavailable; retrying on CPU", e)
            }

            var cpuEngine: EmbeddingEngine? = null
            try {
                val candidate =
                    EmbeddingEngine(
                        EmbeddingEngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(),
                        ),
                    )
                cpuEngine = candidate
                candidate.initialize()
                Log.i(TAG, "Generic EmbeddingGemma 2 initialized with CPU backend")
                return EmbeddingGemma2LocalEmbeddingService(candidate, "CPU", "generic-8c9f652")
            } catch (e: Exception) {
                cpuEngine?.let { runCatching { it.close() } }
                throw IOException("Generic EmbeddingGemma 2 could not initialize on GPU or CPU", e).also {
                    gpuFailure?.let(it::addSuppressed)
                }
            } catch (e: LinkageError) {
                cpuEngine?.let { runCatching { it.close() } }
                throw IOException("Generic EmbeddingGemma 2 native runtime is unavailable", e).also {
                    gpuFailure?.let(it::addSuppressed)
                }
            }
        }
    }

    private val inferenceMutex = Mutex()

    override fun modelId(): String =
        "local:embeddinggemma-2-text-270m-litertlm:$modelVariant:$EXPECTED_DIMENSION:" +
            EmbeddingGemma2InputPolicy.VERSION

    override fun supportsLegacyVectors(): Boolean = false

    override fun getDimension(): Int = EXPECTED_DIMENSION

    override suspend fun embed(text: String): FloatArray = embedDocument(text, "LOCAL_GEMMA_UNSPECIFIED")

    override suspend fun embed(
        text: String,
        source: String,
    ): FloatArray = embedDocument(text, source)

    override suspend fun embedDocument(
        text: String,
        source: String,
    ): FloatArray = embedWithRole(text, source, isQuery = false)

    override suspend fun embedQuery(
        text: String,
        source: String,
    ): FloatArray = embedWithRole(text, source, isQuery = true)

    private suspend fun embedWithRole(
        text: String,
        source: String,
        isQuery: Boolean,
    ): FloatArray =
        withContext(Dispatchers.Default) {
            val traceId = ApiCallTrace.newId()
            val startedAt = System.currentTimeMillis()
            ApiCallTrace.log(
                traceId,
                source,
                "START",
                "engine=LiteRT-LM model=EmbeddingGemma2 network=false",
            )
            try {
                val normalizedText = text.trim()
                val input = InputData.Text(EmbeddingGemma2InputPolicy.format(normalizedText, isQuery))
                val vector =
                    inferenceMutex.withLock {
                        val embedding = engine.computeEmbedding(listOf(input)).embedding
                        FloatArray(embedding.size) { embedding[it].toFloat() }
                    }
                require(vector.isNotEmpty() && vector.all { it.isFinite() }) {
                    "EmbeddingGemma 2 returned an empty or non-finite vector"
                }
                require(vector.size == EXPECTED_DIMENSION) {
                    "EmbeddingGemma 2 returned ${vector.size} dimensions; expected $EXPECTED_DIMENSION"
                }
                val normalized = normalize(vector)
                val finishedAt = System.currentTimeMillis()
                _runtimeStatus.update { status ->
                    status.copy(
                        successfulInferenceCount = status.successfulInferenceCount + 1,
                        lastInferenceAtMillis = finishedAt,
                        lastInferenceSucceeded = true,
                        errorMessage = null,
                    )
                }
                ApiCallTrace.log(
                    traceId,
                    source,
                    "SUCCESS",
                    "engine=LiteRT-LM model=EmbeddingGemma2 backend=$backendName variant=$modelVariant " +
                        "dimension=${normalized.size} elapsedMs=${finishedAt - startedAt}",
                )
                normalized
            } catch (e: CancellationException) {
                ApiCallTrace.log(traceId, source, "CANCELLED", "engine=LiteRT-LM model=EmbeddingGemma2")
                throw e
            } catch (e: Exception) {
                _runtimeStatus.update { status ->
                    status.copy(
                        lastInferenceAtMillis = System.currentTimeMillis(),
                        lastInferenceSucceeded = false,
                        errorMessage = "推理失败：${e.javaClass.simpleName}",
                    )
                }
                ApiCallTrace.log(
                    traceId,
                    source,
                    "FAILURE",
                    "engine=LiteRT-LM model=EmbeddingGemma2 backend=$backendName error=${e.javaClass.simpleName}",
                )
                throw e
            }
        }

    // LiteRT-LM's input list represents one multimodal embedding, not independent batch rows.
    // Process each document separately; cancellation throws before a partial list can be returned.
    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = embedBatch(texts, "LOCAL_GEMMA_BATCH")

    override suspend fun embedBatch(
        texts: List<String>,
        source: String,
    ): List<FloatArray> = texts.map { embedDocument(it, source) }

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
