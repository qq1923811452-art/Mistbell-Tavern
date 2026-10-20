package com.mistbell.tavern.v3.feature.image

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeneratedImageStoreTest {
    private lateinit var directory: java.io.File
    private lateinit var store: GeneratedImageStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mistbell-generated-images-test").toFile()
        store = GeneratedImageStore(directory) { 1234L }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun savesListsAndExportsInlinePngWithoutChangingBytes() {
        val bytes = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
        val saved = store.save(
            GeneratedImage(base64Data = ONE_PIXEL_PNG_BASE64),
            prompt = "a tiny test image",
            model = "test-image-model",
        )

        assertEquals("image/png", saved.mimeType)
        assertEquals(bytes.size.toLong(), saved.sizeBytes)
        assertEquals(1234L, saved.createdAtEpochMillis)
        assertEquals("test-image-model", saved.model)
        assertEquals(listOf(saved), store.list())

        val exported = ByteArrayOutputStream()
        store.export(saved.id, exported)
        assertTrue(exported.toByteArray().contentEquals(bytes))
    }

    @Test
    fun refusesRemoteUrlInsteadOfFetchingItImplicitly() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.save(
                GeneratedImage(remoteUrl = "https://images.example.test/generated.png"),
                prompt = "remote image",
                model = "test-model",
            )
        }
        assertTrue(error.message.orEmpty().contains("not downloaded automatically"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun rejectsOversizedBase64BeforeDecoding() {
        val oversized = "A".repeat(((GeneratedImageStore.MAX_IMAGE_BYTES + 2) / 3) * 4 + 4)
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.save(GeneratedImage(base64Data = oversized), prompt = "large image", model = null)
        }

        assertTrue(error.message.orEmpty().contains("Encoded image exceeds"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun rejectsNonImageDataAndInvalidBase64() {
        assertThrows(IllegalArgumentException::class.java) {
            store.save(GeneratedImage(base64Data = Base64.getEncoder().encodeToString("not an image".toByteArray())),
                prompt = "invalid", model = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(GeneratedImage(base64Data = "not base64!"), prompt = "invalid", model = null)
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun deleteRemovesImageAndMetadata() {
        val saved = store.save(
            GeneratedImage(base64Data = ONE_PIXEL_PNG_BASE64),
            prompt = "temporary",
            model = null,
        )

        assertTrue(store.delete(saved.id))
        assertTrue(store.list().isEmpty())
        assertThrows(java.io.IOException::class.java) {
            store.export(saved.id, ByteArrayOutputStream())
        }
        assertFalse(java.io.File(directory, saved.fileName).exists())
    }

    @Test
    fun exportRejectsMalformedIds() {
        assertThrows(IllegalArgumentException::class.java) {
            store.export("../outside", ByteArrayOutputStream())
        }
    }

    private companion object {
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/9ioAAAAASUVORK5CYII="
    }
}
