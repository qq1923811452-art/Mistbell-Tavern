package com.mistbell.tavern.v3.feature.embedding

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadedEmbeddingModel(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

class EmbeddingGemma2ModelAssetManager(
    private val modelDirectory: File,
) {
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val digestFile = File(modelDirectory, "$MODEL_FILE_NAME.sha256")
    private val downloadLock = ReentrantLock()

    suspend fun download(
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): DownloadedEmbeddingModel = withContext(Dispatchers.IO) {
        downloadLock.lock()
        try {
        check(modelDirectory.isDirectory || modelDirectory.mkdirs()) {
            "Model directory could not be created."
        }
        val temporaryFile = File(modelDirectory, "$MODEL_FILE_NAME.download")
        val temporaryDigestFile = File(modelDirectory, "$MODEL_FILE_NAME.sha256.tmp")
        if (temporaryDigestFile.exists() && !temporaryDigestFile.delete()) {
            throw IllegalStateException("An incomplete model digest could not be removed.")
        }
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IllegalStateException("An incomplete model download could not be removed.")
        }
        val connection = openSecureConnection()
        try {
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
            Files.move(
                temporaryFile.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            FileOutputStream(temporaryDigestFile).use { output ->
                output.write("$sha256  $MODEL_FILE_NAME\n".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporaryDigestFile.toPath(),
                digestFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            DownloadedEmbeddingModel(modelFile, downloadedBytes, sha256)
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

    suspend fun verifyInstalled(): DownloadedEmbeddingModel? = withContext(Dispatchers.IO) {
        try {
            if (!modelFile.isFile || !digestFile.isFile || modelFile.length() !in 1..MAX_MODEL_BYTES) return@withContext null
            val expected = digestFile.readText(Charsets.UTF_8).trim().substringBefore(' ').trim()
            if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) return@withContext null
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
            if (!actual.equals(expected, ignoreCase = true)) return@withContext null
            DownloadedEmbeddingModel(modelFile, modelFile.length(), actual)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun openSecureConnection(): HttpURLConnection {
        var currentUrl = URL(MODEL_DOWNLOAD_URL)
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
            "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm"
        const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        const val PROGRESS_INTERVAL_BYTES = 1024L * 1024L
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
