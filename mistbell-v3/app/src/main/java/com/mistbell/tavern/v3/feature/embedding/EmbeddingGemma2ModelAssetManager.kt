package com.mistbell.tavern.v3.feature.embedding

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DownloadedEmbeddingModel(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
    /** True only when the bytes match the published SM8650-specific model checksum. */
    val isQualcommSm8650Optimized: Boolean,
)

class EmbeddingGemma2ModelAssetManager(
    private val modelDirectory: File,
    private val preferQualcommSm8650Model: Boolean = false,
) {
    private val selectedModelFileName = if (preferQualcommSm8650Model) {
        QUALCOMM_SM8650_MODEL_FILE_NAME
    } else {
        MODEL_FILE_NAME
    }
    private val selectedModelSha256 = if (preferQualcommSm8650Model) {
        QUALCOMM_SM8650_MODEL_SHA256
    } else {
        PORTABLE_MODEL_SHA256
    }
    private val selectedModelUrl = if (preferQualcommSm8650Model) {
        QUALCOMM_SM8650_MODEL_DOWNLOAD_URL
    } else {
        MODEL_DOWNLOAD_URL
    }
    private val modelFile = File(modelDirectory, selectedModelFileName)
    private val digestFile = File(modelDirectory, "$selectedModelFileName.sha256")
    private val downloadLock = ReentrantLock()

    /** Fast identity check for cache lookup; does not hash or initialize the model. */
    fun cacheProfileId(): String = EmbeddingVectorCache.modelProfileId(modelFile, preferQualcommSm8650Model)

    suspend fun download(
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): DownloadedEmbeddingModel = withContext(Dispatchers.IO) {
        downloadLock.lock()
        try {
        check(modelDirectory.isDirectory || modelDirectory.mkdirs()) {
            "Model directory could not be created."
        }
        val temporaryFile = File(modelDirectory, "$selectedModelFileName.download")
        val temporaryDigestFile = File(modelDirectory, "$selectedModelFileName.sha256.tmp")
        if (temporaryDigestFile.exists() && !temporaryDigestFile.delete()) {
            throw IllegalStateException("An incomplete model digest could not be removed.")
        }
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IllegalStateException("An incomplete model download could not be removed.")
        }
        val connection = openSecureConnection()
        try {
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            check(contentType == null || contentType in ALLOWED_MODEL_CONTENT_TYPES) {
                "Model server returned an unexpected content type."
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            require(totalBytes == null || totalBytes <= MAX_MODEL_BYTES) {
                "Model download exceeds the configured size limit."
            }
            var downloadedBytes = 0L
            var lastProgressBytes = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        require(downloadedBytes <= MAX_MODEL_BYTES) {
                            "Model download exceeds the configured size limit."
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (downloadedBytes - lastProgressBytes >= PROGRESS_INTERVAL_BYTES ||
                            (totalBytes != null && downloadedBytes == totalBytes)
                        ) {
                            onProgress(downloadedBytes, totalBytes)
                            lastProgressBytes = downloadedBytes
                        }
                    }
                    output.fd.sync()
                }
            }
            check(downloadedBytes > 0L) { "Model download returned an empty file." }
            if (totalBytes != null) {
                check(downloadedBytes == totalBytes) {
                    "Model download size mismatch: expected $totalBytes bytes, received $downloadedBytes."
                }
            }
            val sha256 = digest.digest().toHex()
            check(sha256.equals(selectedModelSha256, ignoreCase = true)) {
                "Downloaded model checksum does not match the pinned model for the selected runtime profile; refusing to install it."
            }
            FileOutputStream(temporaryDigestFile).use { output ->
                output.write("$sha256  $selectedModelFileName\n".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            invalidateHashStamp()
            Files.move(
                temporaryFile.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            Files.move(
                temporaryDigestFile.toPath(),
                digestFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            DownloadedEmbeddingModel(
                file = modelFile,
                sizeBytes = downloadedBytes,
                sha256 = sha256,
                isQualcommSm8650Optimized = sha256.equals(QUALCOMM_SM8650_MODEL_SHA256, ignoreCase = true),
            )
        } catch (error: Exception) {
            temporaryFile.delete()
            temporaryDigestFile.delete()
            throw error
        } finally {
            connection.disconnect()
            temporaryFile.delete()
            temporaryDigestFile.delete()
        }
        } finally {
            downloadLock.unlock()
        }
    }

    /** Imports a user-selected .litertlm bundle without requiring a public model download URL. */
    suspend fun importModel(
        sourceName: String,
        input: InputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): DownloadedEmbeddingModel = withContext(Dispatchers.IO) {
        downloadLock.lock()
        try {
            require(
                sourceName.endsWith(".litertlm", ignoreCase = true) &&
                    (sourceName.contains("embeddinggemma", ignoreCase = true) ||
                        sourceName.contains("embedding-gemma", ignoreCase = true)),
            ) {
                "Select an EmbeddingGemma 2 .litertlm model file; its filename must identify EmbeddingGemma."
            }
            check(modelDirectory.isDirectory || modelDirectory.mkdirs()) {
                "Model directory could not be created."
            }
            val temporaryFile = File(modelDirectory, "$selectedModelFileName.import")
            val temporaryDigestFile = File(modelDirectory, "$selectedModelFileName.sha256.tmp")
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                throw IllegalStateException("An incomplete model import could not be removed.")
            }
            if (temporaryDigestFile.exists() && !temporaryDigestFile.delete()) {
                throw IllegalStateException("An incomplete model digest could not be removed.")
            }
            try {
                var importedBytes = 0L
                var lastProgressBytes = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                input.use { source ->
                    FileOutputStream(temporaryFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            importedBytes += count
                            require(importedBytes <= MAX_MODEL_BYTES) {
                                "Selected model exceeds the configured size limit."
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (importedBytes - lastProgressBytes >= PROGRESS_INTERVAL_BYTES) {
                                onProgress(importedBytes, null)
                                lastProgressBytes = importedBytes
                            }
                        }
                        output.fd.sync()
                    }
                }
                check(importedBytes > 0L) { "Selected model file is empty." }
                val sha256 = digest.digest().toHex()
                val isQualcommModel = sha256.equals(QUALCOMM_SM8650_MODEL_SHA256, ignoreCase = true)
                require(isQualcommModel == preferQualcommSm8650Model) {
                    if (preferQualcommSm8650Model) {
                        "The selected runtime profile requires the published Qualcomm SM8650 model bundle."
                    } else {
                        "The Qualcomm SM8650-specific model requires a verified NPU runtime profile; use the portable model for CPU/GPU testing."
                    }
                }
                FileOutputStream(temporaryDigestFile).use { output ->
                    output.write("$sha256  $selectedModelFileName\n".toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                invalidateHashStamp()
                Files.move(
                    temporaryFile.toPath(),
                    modelFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                Files.move(
                    temporaryDigestFile.toPath(),
                    digestFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                DownloadedEmbeddingModel(
                    file = modelFile,
                    sizeBytes = importedBytes,
                    sha256 = sha256,
                    isQualcommSm8650Optimized = sha256.equals(QUALCOMM_SM8650_MODEL_SHA256, ignoreCase = true),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw error
            } finally {
                temporaryFile.delete()
                temporaryDigestFile.delete()
            }
        } finally {
            runCatching { input.close() }
            downloadLock.unlock()
        }
    }

    suspend fun verifyInstalled(): DownloadedEmbeddingModel? = withContext(Dispatchers.IO) {
        try {
            if (!modelFile.isFile || !digestFile.isFile || (modelFile.length() < 1L || modelFile.length() > MAX_MODEL_BYTES)) return@withContext null
            val expected = digestFile.readText(Charsets.UTF_8).trim().substringBefore(' ').trim()
            if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) return@withContext null
            val actual = sha256CachedForInstalledModel(expected)
            if (!actual.equals(expected, ignoreCase = true)) return@withContext null
            val isQualcommModel = actual.equals(QUALCOMM_SM8650_MODEL_SHA256, ignoreCase = true)
            if (isQualcommModel != preferQualcommSm8650Model) return@withContext null
            DownloadedEmbeddingModel(
                file = modelFile,
                sizeBytes = modelFile.length(),
                sha256 = actual,
                isQualcommSm8650Optimized = actual.equals(QUALCOMM_SM8650_MODEL_SHA256, ignoreCase = true),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun invalidateHashStamp() {
        val stamp = File(modelDirectory, "$selectedModelFileName.hashstamp")
        if (stamp.exists()) check(stamp.delete()) { "The previous model hash stamp could not be invalidated." }
    }

    /** Persist verified model digests across restarts; cache misses always perform full SHA-256. */
    private fun sha256CachedForInstalledModel(expectedDigest: String): String {
        val stampFile = File(modelDirectory, "$selectedModelFileName.hashstamp")
        val canonicalPath = modelFile.canonicalPath
        val stampVersion = "1"
        val metadata = "$stampVersion\n$canonicalPath\n${modelFile.length()}\n${modelFile.lastModified()}\n$expectedDigest"
        if (stampFile.isFile) {
            val lines = runCatching { stampFile.readText(Charsets.UTF_8).split('\n') }.getOrDefault(emptyList())
            if (lines.size == 5 && lines[0] == stampVersion && lines[1] == canonicalPath &&
                lines[2] == modelFile.length().toString() && lines[3] == modelFile.lastModified().toString() &&
                lines[4].equals(expectedDigest, ignoreCase = true)
            ) return lines[4].lowercase()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        modelFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().toHex()
        if (actual.equals(expectedDigest, ignoreCase = true)) runCatching {
            val temporary = File(modelDirectory, "$selectedModelFileName.hashstamp.tmp")
            temporary.writeText(metadata.replace(expectedDigest, actual), Charsets.UTF_8)
            if (stampFile.exists()) stampFile.delete()
            check(temporary.renameTo(stampFile))
        }
        return actual
    }
    private fun openSecureConnection(): HttpURLConnection {
        var currentUrl = URL(selectedModelUrl)
        for (redirectCount in 0..MAX_REDIRECTS) {
            check(currentUrl.protocol.equals("https", ignoreCase = true)) {
                "Model download redirects must use HTTPS."
            }
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mistbell-Tavern-3.0")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    check(!location.isNullOrBlank()) { "Model server returned a redirect without a location." }
                    val nextUrl = URL(currentUrl, location)
                    check(nextUrl.protocol.equals("https", ignoreCase = true)) {
                        "Model download redirected to a non-HTTPS URL."
                    }
                    check(redirectCount < MAX_REDIRECTS) { "Model download exceeded the redirect limit." }
                    currentUrl = nextUrl
                    continue
                }
                check(code == HttpURLConnection.HTTP_OK) { "Model download failed with HTTP $code." }
                check(connection.url.protocol.equals("https", ignoreCase = true)) {
                    "Model download did not remain on HTTPS."
                }
                return connection
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IllegalStateException("Model download exceeded the redirect limit.")
    }

    companion object {
        const val MODEL_FILE_NAME = "embeddinggemma-2-740m.litertlm"
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/86b903a/embeddinggemma-2-740m.litertlm"
        const val PORTABLE_MODEL_SHA256 =
            "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"
        const val QUALCOMM_SM8650_MODEL_FILE_NAME = "embeddinggemma-2-740m_Qualcomm_SM8650.litertlm"
        const val QUALCOMM_SM8650_MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/ed3f5e1/embeddinggemma-2-740m_Qualcomm_SM8650.litertlm"
        const val QUALCOMM_SM8650_MODEL_SHA256 =
            "3b8a18c6d043b8132c4eda45611296e1ae455ada82cda8b591ec0ca141e041c5"
        private val ALLOWED_MODEL_CONTENT_TYPES = setOf(
            "application/octet-stream",
            "application/x-litertlm",
            "application/vnd.google.litertlm",
        )
        const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        const val PROGRESS_INTERVAL_BYTES = 1024L * 1024L
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
