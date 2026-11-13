package com.mistbell.tavern.v3.feature.character

import java.util.Base64
import java.util.Locale

/** Zero-dependency reader for SillyTavern PNG character cards (`chara` / `ccv3` text chunks). */
object PngCharacterCardCodec {
    private const val CHUNK_HEADER_SIZE = 8
    private const val CRC_SIZE = 4
    private const val INTEGER_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val MAX_CHUNK_LENGTH = 64 * 1024 * 1024
    private const val COMPRESSION_FLAGS_BYTES = 2
    private const val KEY_CCV3 = "ccv3"
    private const val KEY_CHARA = "chara"
    private val PNG_SIGNATURE = "\u0089PNG\r\n\u001a\n".toByteArray(Charsets.ISO_8859_1)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { index -> bytes[index] == PNG_SIGNATURE[index] }

    fun extractCardJson(pngBytes: ByteArray): String? {
        if (!isPng(pngBytes)) return null
        val chunks = parseTextChunks(pngBytes)
        return chunks.firstOrNull { it.first == KEY_CCV3 }?.second
            ?: chunks.firstOrNull { it.first == KEY_CHARA }?.second
    }

    private fun parseTextChunks(pngBytes: ByteArray): List<Pair<String, String>> =
        buildList {
            var offset = PNG_SIGNATURE.size
            while (offset + CHUNK_HEADER_SIZE + CRC_SIZE <= pngBytes.size) {
                val length = readInt(pngBytes, offset)
                val dataStart = offset + CHUNK_HEADER_SIZE
                if (length !in 0..MAX_CHUNK_LENGTH || length > pngBytes.size - dataStart - CRC_SIZE) break
                val dataEnd = dataStart + length
                val type = String(pngBytes, offset + INTEGER_BYTES, INTEGER_BYTES, Charsets.US_ASCII)
                val decoded =
                    when (type) {
                        "tEXt" -> decodeTextChunk(pngBytes, dataStart, dataEnd)
                        "iTXt" -> decodeInternationalTextChunk(pngBytes, dataStart, dataEnd)
                        else -> null
                    }
                decoded?.let { add(it) }
                offset = dataEnd + CRC_SIZE
            }
        }

    private fun decodeTextChunk(
        pngBytes: ByteArray,
        start: Int,
        end: Int,
    ): Pair<String, String>? {
        val keywordEnd = indexOfNul(pngBytes, start, end)
        val text =
            if (keywordEnd != null && keywordEnd + 1 <= end) {
                String(pngBytes, keywordEnd + 1, end - keywordEnd - 1, Charsets.ISO_8859_1).trim()
            } else {
                ""
            }
        val json = decodeBase64(text)
        return if (keywordEnd != null && json != null) {
            keyword(pngBytes, start, keywordEnd) to json
        } else {
            null
        }
    }

    private fun decodeInternationalTextChunk(
        pngBytes: ByteArray,
        start: Int,
        end: Int,
    ): Pair<String, String>? {
        val keywordEnd = indexOfNul(pngBytes, start, end)
        val flagsStart = (keywordEnd ?: 0) + 1
        val flagsEnd = flagsStart + COMPRESSION_FLAGS_BYTES
        val validFlags = keywordEnd != null && flagsEnd <= end
        val compressed = validFlags && pngBytes[flagsStart].toInt() != 0
        val languageEnd = if (validFlags) indexOfNul(pngBytes, flagsEnd, end) else null
        val translatedEnd = languageEnd?.let { indexOfNul(pngBytes, it + 1, end) }
        val text =
            translatedEnd?.let { textStart ->
                String(pngBytes, textStart + 1, end - textStart - 1, Charsets.UTF_8).trim()
            }
        val json = text?.let(::decodeBase64)
        return if (keywordEnd != null && !compressed && json != null) {
            keyword(pngBytes, start, keywordEnd) to json
        } else {
            null
        }
    }

    private fun keyword(
        pngBytes: ByteArray,
        start: Int,
        end: Int,
    ): String = String(pngBytes, start, end - start, Charsets.ISO_8859_1).lowercase(Locale.ROOT)

    private fun decodeBase64(value: String): String? =
        runCatching { Base64.getMimeDecoder().decode(value) }
            .getOrNull()
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun indexOfNul(
        pngBytes: ByteArray,
        start: Int,
        end: Int,
    ): Int? = (start until end).firstOrNull { index -> pngBytes[index].toInt() == 0 }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        repeat(INTEGER_BYTES) { index ->
            value = (value shl BITS_PER_BYTE) or (bytes[offset + index].toInt() and BYTE_MASK)
        }
        return value
    }
}
