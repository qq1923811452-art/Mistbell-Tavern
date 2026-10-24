package com.mistbell.tavern.v3.feature.embedding

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingGemma2ModelAssetManagerTest {
    @Test
    fun acceptsInstalledModelWhenStoredDigestMatches(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-test").toFile()
        try {
            writeModelAndDigest(directory, "valid-model-bundle".toByteArray())
            val result = EmbeddingGemma2ModelAssetManager(directory).verifyInstalled()

            assertNotNull(result)
            assertEquals("valid-model-bundle".length.toLong(), result!!.sizeBytes)
            assertFalse(result.isQualcommSm8650Optimized)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importsLocalLitertlmAndWritesVerifiableDigest(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-import-test").toFile()
        try {
            val bytes = "local-litertlm-bundle".toByteArray()
            val manager = EmbeddingGemma2ModelAssetManager(directory, allowCustomModel = true)
            val imported = manager.importModel(
                "embeddinggemma-2-740m.litertlm",
                ByteArrayInputStream(bytes),
            )

            assertEquals(bytes.size.toLong(), imported.sizeBytes)
            assertFalse(imported.isQualcommSm8650Optimized)
            assertEquals(imported.sha256, manager.verifyInstalled()!!.sha256)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localImportCopiesIntoPrivateDirectoryAndLeavesPublicSourceUntouched(): Unit = runBlocking {
        val publicDirectory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-public-source").toFile()
        val appDirectory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-app-private").toFile()
        try {
            val bytes = "local-litertlm-bundle".toByteArray()
            val source = File(publicDirectory, "embeddinggemma-2-740m.litertlm").apply { writeBytes(bytes) }
            val imported = EmbeddingGemma2ModelAssetManager(
                appDirectory,
                allowCustomModel = true,
            ).importModel(
                source.name,
                source.inputStream(),
            )

            assertEquals(appDirectory.canonicalFile, imported.file.parentFile.canonicalFile)
            assertEquals(bytes.size.toLong(), imported.sizeBytes)
            assertTrue(source.exists())
            assertEquals(bytes.toList(), source.readBytes().toList())
            assertEquals(listOf(source.name), publicDirectory.list()?.toList())
            assertTrue(File(appDirectory, "${EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME}.sha256").isFile)
        } finally {
            publicDirectory.deleteRecursively()
            appDirectory.deleteRecursively()
        }
    }
    @Test
    fun rejectsUnrelatedLitertlmFilename(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-import-test").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    EmbeddingGemma2ModelAssetManager(directory).importModel(
                        "unrelated-model.litertlm",
                        ByteArrayInputStream("not-embeddinggemma".toByteArray()),
                    )
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun importedCustomModelStillVerifiesAfterTheSwitchIsClosed(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-custom-load").toFile()
        try {
            val source = File(directory, "embeddinggemma-2-740m.litertlm")
                .apply { writeBytes("custom-bundle".toByteArray()) }
            EmbeddingGemma2ModelAssetManager(directory, allowCustomModel = true).importModel(
                source.name,
                source.inputStream(),
            )
            val verified = EmbeddingGemma2ModelAssetManager(directory).verifyInstalled()
            assertEquals("custom-bundle".length.toLong(), verified!!.sizeBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonOfficialPortableModelUnlessCustomImportIsAllowed(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-policy").toFile()
        try {
            val source = File(directory, "embeddinggemma-2-740m.litertlm")
                .apply { writeBytes("custom-bundle".toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    EmbeddingGemma2ModelAssetManager(directory).importModel(
                        source.name,
                        source.inputStream(),
                    )
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsInstalledModelWhenBytesChangeAfterDownload(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-test").toFile()
        try {
            val file = writeModelAndDigest(directory, "original-model-bundle".toByteArray())
            file.writeText("tampered-model-bundle")
            val result = EmbeddingGemma2ModelAssetManager(directory).verifyInstalled()

            assertNull(result)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun portableAndSm8650DownloadsArePinnedToTheirPublishedBundles(): Unit {
        assertTrue(EmbeddingGemma2ModelAssetManager.MODEL_DOWNLOAD_URL.contains("86b903a/embeddinggemma-2-740m.litertlm"))
        assertEquals(64, EmbeddingGemma2ModelAssetManager.PORTABLE_MODEL_SHA256.length)
        assertTrue(EmbeddingGemma2ModelAssetManager.QUALCOMM_SM8650_MODEL_DOWNLOAD_URL.contains("ed3f5e1/embeddinggemma-2-740m_Qualcomm_SM8650.litertlm"))
        assertEquals(64, EmbeddingGemma2ModelAssetManager.QUALCOMM_SM8650_MODEL_SHA256.length)
    }

    @Test
    fun cancelledImportRemovesTemporaryFilesAndReleasesTransferLock(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-cancel-test").toFile()
        try {
            val manager = EmbeddingGemma2ModelAssetManager(directory, allowCustomModel = true)
            val largeBytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            lateinit var importJob: Job

            importJob = launch {
                manager.importModel(
                    "embeddinggemma-2-740m.litertlm",
                    ByteArrayInputStream(largeBytes),
                    onProgress = { _, _ -> importJob.cancel() },
                )
            }
            importJob.join()

            assertTrue(importJob.isCancelled)
            assertFalse(File(directory, "${EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME}.import").exists())
            assertFalse(File(directory, "${EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME}.sha256.tmp").exists())
            assertFalse(File(directory, EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME).exists())

            val validRetryBytes = "retry-after-cancellation".toByteArray()
            val retried = manager.importModel(
                "embeddinggemma-2-740m.litertlm",
                ByteArrayInputStream(validRetryBytes),
            )
            assertEquals(validRetryBytes.size.toLong(), retried.sizeBytes)
            assertEquals(retried.sha256, manager.verifyInstalled()!!.sha256)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistsVerifiedModelHashStampAndReusesItOnSubsequentChecks(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-hash-cache-test").toFile()
        try {
            writeModelAndDigest(directory, "valid-model-bundle".toByteArray())
            val manager = EmbeddingGemma2ModelAssetManager(directory)
            val first = manager.verifyInstalled()
            val stamp = File(directory, "${EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME}.hashstamp")

            assertNotNull(first)
            assertTrue("Successful verification should persist a hash stamp", stamp.isFile)
            val savedStamp = stamp.readText()
            val second = manager.verifyInstalled()

            assertNotNull(second)
            assertEquals(first!!.sha256, second!!.sha256)
            assertEquals("Unchanged model metadata should retain the cached stamp", savedStamp, stamp.readText())
        } finally {
            directory.deleteRecursively()
        }
    }


    private fun writeModelAndDigest(directory: File, bytes: ByteArray): File {
        val model = File(directory, EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME)
        model.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        File(directory, "${EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME}.sha256")
            .writeText("$hash  ${model.name}\n")
        return model
    }
}
