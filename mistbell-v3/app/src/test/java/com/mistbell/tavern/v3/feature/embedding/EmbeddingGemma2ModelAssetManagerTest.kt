package com.mistbell.tavern.v3.feature.embedding

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingGemma2ModelAssetManagerTest {
    @Test
    fun acceptsInstalledModelWhenStoredDigestMatches() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-test").toFile()
        try {
            writeModelAndDigest(directory, "valid-model-bundle".toByteArray())
            val result = EmbeddingGemma2ModelAssetManager(directory).verifyInstalled()

            assertNotNull(result)
            assertEquals("valid-model-bundle".length.toLong(), result!!.sizeBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importsLocalLitertlmAndWritesVerifiableDigest() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(prefix = "embedding-model-import-test").toFile()
        try {
            val bytes = "local-litertlm-bundle".toByteArray()
            val manager = EmbeddingGemma2ModelAssetManager(directory)
            val imported = manager.importModel(
                "embeddinggemma-2-740m.litertlm",
                ByteArrayInputStream(bytes),
            )

            assertEquals(bytes.size.toLong(), imported.sizeBytes)
            assertEquals(imported.sha256, manager.verifyInstalled()!!.sha256)
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun rejectsUnrelatedLitertlmFilename() = runBlocking {
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
    fun rejectsInstalledModelWhenBytesChangeAfterDownload() = runBlocking {
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
