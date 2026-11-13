package com.mistbell.tavern.v3.feature.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class PngCharacterCardCodecTest {
    private val json = """{"name":"Luna","description":"moon"}"""

    @Test
    fun readsCharaAndCcv3TextChunksWithCcv3Priority() {
        val charaOnly = png(textChunk("chara", json))
        assertTrue(PngCharacterCardCodec.isPng(charaOnly))
        assertEquals(json, PngCharacterCardCodec.extractCardJson(charaOnly))

        val withBoth = png(textChunk("chara", """{"name":"old"}"""), textChunk("ccv3", json))
        assertEquals(json, PngCharacterCardCodec.extractCardJson(withBoth))
    }

    @Test
    fun readsUncompressedInternationalTextChunk() {
        val png = png(internationalTextChunk("ccv3", json))

        assertEquals(json, PngCharacterCardCodec.extractCardJson(png))
    }

    @Test
    fun rejectsNonPngAndPngWithoutCardText() {
        assertFalse(PngCharacterCardCodec.isPng("not a png".toByteArray()))
        assertNull(PngCharacterCardCodec.extractCardJson("not a png".toByteArray()))
        assertNull(PngCharacterCardCodec.extractCardJson(png()))
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        var result = PNG_SIGNATURE
        chunks.forEach { chunk -> result += chunk }
        return result
    }

    private fun textChunk(
        keyword: String,
        json: String,
    ): ByteArray = chunk("tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + base64(json))

    private fun internationalTextChunk(
        keyword: String,
        json: String,
    ): ByteArray =
        chunk(
            "iTXt",
            keyword.toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0, 0, 0, 0, 0) +
                base64(json),
        )

    private fun chunk(
        type: String,
        payload: ByteArray,
    ): ByteArray {
        val header = ByteBuffer.allocate(8).putInt(payload.size).put(type.toByteArray(Charsets.US_ASCII)).array()
        return header + payload + ByteArray(4)
    }

    private fun base64(json: String): ByteArray = Base64.getEncoder().encode(json.toByteArray(Charsets.UTF_8))

    private companion object {
        val PNG_SIGNATURE: ByteArray = "\u0089PNG\r\n\u001a\n".toByteArray(Charsets.ISO_8859_1)
    }
}
