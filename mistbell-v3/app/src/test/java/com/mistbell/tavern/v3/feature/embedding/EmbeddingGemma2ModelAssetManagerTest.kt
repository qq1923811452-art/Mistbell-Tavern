package com.mistbell.tavern.v3.feature.embedding

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddingGemma2ModelAssetManagerTest {
    @Test
    fun acceptsInstalledModelWhenStoredDigestMatches() = runBlocking {
        val directory = createTempDir(prefix = "embedding-model-test")
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
    fun rejectsInstalledModelWhenBytesChangeAfterDownload() = runBlocking {
        val directory = createTempDir(prefix = "embedding-model-test")
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
