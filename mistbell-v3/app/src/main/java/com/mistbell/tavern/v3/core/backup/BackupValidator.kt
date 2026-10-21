package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.security.MessageDigest

/** Security limits and pure validation shared by archive creation, preview and restore. */
object BackupValidator {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_PATH_CHARS = 1024
    const val MAX_SEGMENT_CHARS = 255

    fun validatePath(path: String) {
        require(path.isNotEmpty() && path.length <= MAX_PATH_CHARS) { "Backup path is empty or too long." }
        require(!path.startsWith("/") && !path.startsWith("\\\\") && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
            "Absolute backup paths are not allowed."
        }
        require('\\' !in path && '\u0000' !in path) { "Backup path contains an invalid character." }
        val segments = path.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && it.length <= MAX_SEGMENT_CHARS }) {
            "Backup path contains an invalid segment."
        }
        require(path.startsWith("data/")) { "Backup entry is outside the data directory." }
    }

    fun validateManifest(manifest: BackupManifest) {
        require(manifest.format == BackupManifest.FORMAT) { "Unsupported backup format." }
        require(manifest.version == BackupManifest.CURRENT_VERSION) {
            "Unsupported backup version: v${manifest.version}."
        }
        require(manifest.entryCount == manifest.entries.size) { "Backup manifest entry count does not match." }
        require(manifest.entryCount in 1..MAX_ENTRIES) { "Backup entry count exceeds the limit." }
        require(manifest.totalBytes in 0..MAX_TOTAL_UNCOMPRESSED_BYTES) {
            "Backup uncompressed size exceeds the limit."
        }
        val paths = HashSet<String>()
        var sum = 0L
        manifest.entries.forEach { entry ->
            validatePath(entry.path)
            require(paths.add(entry.path)) { "Backup manifest contains duplicate paths." }
            require(entry.size in 0..MAX_ENTRY_BYTES) { "Backup entry exceeds the size limit." }
            require(entry.sha256.matches(Regex("[a-f0-9]{64}"))) { "Backup entry SHA-256 is invalid." }
            sum = Math.addExact(sum, entry.size)
            require(sum <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Backup uncompressed size exceeds the limit." }
        }
        require(sum == manifest.totalBytes) { "Backup manifest total size does not match." }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
