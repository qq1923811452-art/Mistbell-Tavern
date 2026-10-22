package com.mistbell.tavern.v3.feature.character

import java.io.File
import java.security.MessageDigest

/**
 * Stores character avatars as files keyed by a stable hash of the character name, so renaming a
 * card file does not orphan its picture and repeated imports overwrite in place.
 */
class AvatarStore(private val directory: File) {

    fun save(characterName: String, bytes: ByteArray): String {
        require(characterName.isNotBlank()) { "Character name is required." }
        require(bytes.isNotEmpty()) { "Avatar data is empty." }
        check(directory.isDirectory || directory.mkdirs()) { "Avatar directory could not be created." }
        val fileName = fileNameFor(characterName)
        val target = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target)) { "Avatar could not be stored." }
        return fileName
    }

    fun load(fileName: String?): ByteArray? {
        if (fileName.isNullOrBlank()) return null
        val file = File(directory, fileName)
        return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    fun exists(fileName: String?): Boolean =
        !fileName.isNullOrBlank() && File(directory, fileName).isFile

    fun delete(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        return runCatching { File(directory, fileName).delete() }.getOrDefault(false)
    }

    fun fileNameFor(characterName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(characterName.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) } + ".webp"
    }
}
