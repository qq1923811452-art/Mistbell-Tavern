@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.feature.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Which picture of a character is being stored. */
enum class CharacterArtKind(val suffix: String) {
    Avatar("avatar"),
    Portrait("portrait"),
}

/**
 * Private-file store for per-character artwork.
 *
 * Files are keyed by a stable hash of (character name + kind) so renaming a card file never orphans
 * its picture and re-importing the same picture overwrites in place instead of piling up copies.
 */
class CharacterArtStore(private val directory: File) {
    fun save(
        kind: CharacterArtKind,
        characterName: String,
        bytes: ByteArray,
    ): String {
        require(characterName.isNotBlank()) { "Character name is required." }
        require(bytes.isNotEmpty()) { "Character art data is empty." }
        check(directory.isDirectory || directory.mkdirs()) { "Character art directory could not be created." }
        val fileName = fileNameFor(kind, characterName)
        val target = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target)) { "Character art could not be stored." }
        CharacterArtMemory.remember(fileName, bytes)
        return fileName
    }

    fun load(fileName: String?): ByteArray? {
        if (fileName.isNullOrBlank()) return null
        val file = File(directory, fileName)
        return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    fun exists(fileName: String?): Boolean = !fileName.isNullOrBlank() && File(directory, fileName).isFile

    fun delete(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        CharacterArtMemory.forget(fileName)
        return runCatching { File(directory, fileName).delete() }.getOrDefault(false)
    }

    fun fileNameFor(
        kind: CharacterArtKind,
        characterName: String,
    ): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest((kind.suffix + ":" + characterName.trim()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) } + ".webp"
    }

    companion object {
        /** Directory under `filesDir` shared by every reader in the app. */
        const val DIRECTORY_NAME = "characters/art"

        fun directoryOf(filesDir: File): File = File(filesDir, DIRECTORY_NAME)
    }
}

/**
 * Process-wide cache of artwork bytes so a `LazyColumn` of session rows reads each file once
 * instead of on every frame. Missing files are cached as misses to stop repeated disk probes.
 */
object CharacterArtMemory {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun peek(fileName: String?): ByteArray? = fileName?.let { name -> cache[name]?.takeIf(ByteArray::isNotEmpty) }

    fun getOrLoad(
        filesDir: File,
        fileName: String?,
    ): ByteArray? =
        fileName?.let { name ->
            cache[name]?.takeIf(ByteArray::isNotEmpty) ?: runCatching {
                File(CharacterArtStore.directoryOf(filesDir), name).takeIf(File::isFile)?.readBytes()
            }.getOrNull().also { cache[name] = it ?: MISS }
        }

    fun remember(
        fileName: String,
        bytes: ByteArray,
    ) {
        cache[fileName] = bytes
    }

    fun forget(fileName: String) {
        cache.remove(fileName)
    }

    private val MISS = ByteArray(0)
}

/** Decodes artwork bytes; `null` in, `null` out. */
internal fun decodeCharacterArt(bytes: ByteArray?): Bitmap? =
    bytes?.let { data -> runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull() }
