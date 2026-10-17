package com.mistbell.tavern.v3.feature.embedding

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
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
            val manager = EmbeddingGemma2ModelAssetManager(directory)
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
    fun usingLocalModelKeepsOriginalFileAndDoesNotCreateDuplicateOrDigest(): Unit = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-local-source").toFile()
        val appDirectory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-app-cache").toFile()
        try {
            val bytes = "existing-local-model-bundle".toByteArray()
            val source = File(directory, "embeddinggemma-2-740m.litertlm").apply { writeBytes(bytes) }
            val manager = EmbeddingGemma2ModelAssetManager(appDirectory)
            val result = manager.useLocalModel(source)

            assertEquals(source.canonicalFile, result.file)
            assertEquals(source.canonicalFile, result.file.canonicalFile)
            assertEquals(bytes.size.toLong(), result.sizeBytes)
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                result.sha256,
            )
            assertTrue(source.exists())
            assertEquals(listOf(source.name), directory.list()?.toList())
            assertFalse(File(appDirectory, EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME).exists())
            assertFalse(File(appDirectory, "${EmbeddingGemma2ModelAssetManager.MODEL_FILE_NAME}.sha256").exists())
        } finally {
            directory.deleteRecursively()
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
