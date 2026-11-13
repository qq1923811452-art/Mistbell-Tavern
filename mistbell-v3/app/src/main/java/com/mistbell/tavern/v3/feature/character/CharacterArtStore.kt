@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.feature.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Which picture of a character is being stored. */
enum class CharacterArtKind(val suffix: String) {
    Avatar("avatar"),
    Portrait("portrait"),
}

/**
 * Private-file store for per-character artwork.
 *
 * Content-addressed files keep same-name cards independent and make artwork replacement observable
 * to Compose readers. Identical images share a file without overwriting another card's artwork.
 */
class CharacterArtStore(private val directory: File) {
    fun save(
        kind: CharacterArtKind,
        characterName: String,
        bytes: ByteArray,
    ): String {
        require(characterName.isNotBlank()) { "Character name is required." }
        require(bytes.isNotEmpty()) { "Character art data is empty." }
        require(bytes.size <= MAX_ART_BYTES) { "图片超过 8 MiB 限制。" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码所选图片。" }
        check(directory.isDirectory || directory.mkdirs()) { "Character art directory could not be created." }
        val fileName = characterArtFileName(kind, bytes)
        val target = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target)) { "Character art could not be stored." }
        CharacterArtMemory.remember(fileName, bytes)
        return fileName
    }

    fun load(fileName: String?): ByteArray? {
        val file = safeFile(fileName) ?: return null
        return runCatching {
            if (file.isFile && file.length() in 1..MAX_ART_BYTES.toLong()) file.readBytes() else null
        }.getOrNull()
    }

    fun exists(fileName: String?): Boolean = safeFile(fileName)?.isFile == true

    fun delete(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        CharacterArtMemory.forget(fileName)
        return runCatching { safeFile(fileName)?.delete() == true }.getOrDefault(false)
    }

    private fun safeFile(fileName: String?): File? =
        fileName?.takeIf { it.isNotBlank() && it == File(it).name && it != "." && it != ".." }
            ?.let { File(directory, it) }

    private fun characterArtFileName(
        kind: CharacterArtKind,
        bytes: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
        return "$hex-${kind.suffix}.img"
    }

    companion object {
        /** Directory under `filesDir` shared by every reader in the app. */
        const val DIRECTORY_NAME = "characters/art"
        const val MAX_ART_BYTES = 8 * 1024 * 1024

        fun directoryOf(filesDir: File): File = File(filesDir, DIRECTORY_NAME)
    }
}

/**
 * Process-wide bounded cache of artwork bytes. Missing files are cached as misses to stop repeated
 * disk probes, and the byte budget prevents a large library from retaining every original image.
 */
object CharacterArtMemory {
    private val cache =
        object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: ByteArray,
            ): Int = value.size.coerceAtLeast(1)
        }

    fun peek(fileName: String?): ByteArray? = fileName?.let { cache.get(it)?.takeIf(ByteArray::isNotEmpty) }

    fun getOrLoad(
        filesDir: File,
        fileName: String?,
    ): ByteArray? =
        fileName?.let { name ->
            (
                cache.get(name) ?: CharacterArtStore(CharacterArtStore.directoryOf(filesDir)).load(name)
                    .also { cache.put(name, it ?: MISS) }
            )?.takeIf(ByteArray::isNotEmpty)
        }

    fun remember(
        fileName: String,
        bytes: ByteArray,
    ) {
        cache.put(fileName, bytes)
    }

    fun forget(fileName: String) {
        cache.remove(fileName)
    }

    fun clear() {
        cache.evictAll()
    }

    private val MISS = ByteArray(0)
}

/** Loads and downsamples an image away from the UI thread. */
suspend fun loadCharacterArtBitmap(
    filesDir: File,
    fileName: String?,
    maxDimensionPx: Int,
): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val bytes = CharacterArtMemory.getOrLoad(filesDir, fileName) ?: return@withContext null
        decodeCharacterArt(bytes, maxDimensionPx)?.asImageBitmap()
    }

/** Decodes artwork bytes with a power-of-two sample size to bound bitmap memory. */
internal fun decodeCharacterArt(
    bytes: ByteArray,
    maxDimensionPx: Int,
): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimensionPx || bounds.outHeight / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
