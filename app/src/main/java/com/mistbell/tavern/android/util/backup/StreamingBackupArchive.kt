package com.mistbell.tavern.android.util.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * File-backed archive operations for large backups. No payload or complete ZIP is buffered in memory.
 * Restore extracts only into a caller-owned staging directory and returns only after checksum validation.
 */
object StreamingBackupArchive {
    private const val MANIFEST = "META-INF/mistbell-backup-manifest.tsv"
    private const val HEADER = "mistbell-backup\t1"
    private const val BUFFER = 64 * 1024
    private const val MAX_MANIFEST = 16L * 1024 * 1024
    private const val MAX_PATH_BYTES = 1024
    private const val MAX_SEGMENT = 255
    private val shaPattern = Regex("[0-9a-f]{64}")

    const val MAX_ENTRIES = 10_000
    const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    private data class Metadata(val size: Long, val sha256: String)
    data class RestoreResult(val createdAtEpochMs: Long, val stagedFiles: Map<String, File>)

    @Throws(IOException::class)
    fun write(output: OutputStream, sourceFiles: Map<String, File>, createdAtEpochMs: Long) {
        if (createdAtEpochMs < 0) throw IOException("Invalid backup timestamp")
        if (sourceFiles.size + 1 > MAX_ENTRIES) throw IOException("Too many backup entries")
        val sorted = sourceFiles.toSortedMap()
        val metadata = LinkedHashMap<String, Metadata>()
        var total = 0L
        sorted.forEach { (path, file) ->
            validatePath(path)
            if (path == MANIFEST) throw IOException("Reserved backup path")
            if (!file.isFile || !file.canRead()) throw IOException("Backup source is not readable: $path")
            val size = file.length()
            if (size > MAX_ENTRY_BYTES) throw IOException("Backup entry is too large: $path")
            total = checkedTotal(total, size)
            metadata[path] = Metadata(size, digest(file))
        }
        ZipOutputStream(output).use { zip ->
            sorted.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().buffered(BUFFER).use { input -> copyBounded(input, zip, file.length()) }
                zip.closeEntry()
            }
            val manifest = buildString {
                append(HEADER).append('\t').append(createdAtEpochMs).append('\n')
                metadata.forEach { (path, item) ->
                    val encoded = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
                    append(encoded).append('\t').append(item.size).append('\t').append(item.sha256).append('\n')
                }
            }.toByteArray(StandardCharsets.UTF_8)
            if (manifest.size.toLong() > MAX_MANIFEST) throw IOException("Backup manifest is too large")
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
        }
    }

    @Throws(IOException::class)
    fun restore(input: InputStream, stagingDirectory: File): RestoreResult {
        if (stagingDirectory.exists() &&
            (!stagingDirectory.isDirectory || stagingDirectory.list()?.isNotEmpty() == true)
        ) throw IOException("Restore staging directory must be empty")
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw IOException("Cannot create restore staging directory")
        }
        val root = stagingDirectory.canonicalFile
        val files = LinkedHashMap<String, File>()
        var manifestBytes: ByteArray? = null
        var total = 0L
        try {
            ZipInputStream(input.buffered(BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = safePath(entry.name)
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (path == MANIFEST) {
                        if (manifestBytes != null) throw IOException("Duplicate backup manifest")
                        manifestBytes = readBounded(zip, MAX_MANIFEST)
                    } else {
                        if (files.containsKey(path)) throw IOException("Duplicate backup entry: $path")
                        if (files.size + 1 >= MAX_ENTRIES) throw IOException("Too many backup entries")
                        val destination = File(root, path).canonicalFile
                        if (!destination.toPath().startsWith(root.toPath())) throw IOException("Unsafe backup path")
                        destination.parentFile?.let {
                            if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create staging path")
                        }
                        var entrySize = 0L
                        destination.outputStream().buffered(BUFFER).use { out ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                entrySize += count
                                if (entrySize > MAX_ENTRY_BYTES) throw IOException("Backup entry exceeds size limit")
                                total = checkedTotal(total, count.toLong())
                                out.write(buffer, 0, count)
                            }
                        }
                        files[path] = destination
                    }
                    zip.closeEntry()
                }
            }
            val parsed = parseManifest(manifestBytes ?: throw IOException("Backup manifest is missing"))
            if (parsed.second.keys != files.keys) throw IOException("Backup contents do not match manifest")
            files.forEach { (path, file) ->
                val expected = parsed.second[path] ?: throw IOException("Unlisted backup entry")
                if (file.length() != expected.size || digest(file) != expected.sha256) {
                    throw IOException("Backup checksum mismatch: $path")
                }
            }
            return RestoreResult(parsed.first, files.toMap())
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            if (error is IOException) throw error
            throw IOException("Backup restore validation failed", error)
        }
    }

    private fun parseManifest(bytes: ByteArray): Pair<Long, Map<String, Metadata>> {
        val lines = bytes.toString(StandardCharsets.UTF_8).lineSequence().filter(String::isNotEmpty).toList()
        val header = lines.firstOrNull()?.split('\t') ?: throw IOException("Backup manifest is empty")
        if (header.size != 3 || "${header[0]}\t${header[1]}" != HEADER) {
            throw IOException("Unsupported backup manifest")
        }
        val timestamp = header[2].toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid backup timestamp")
        if (lines.size > MAX_ENTRIES) throw IOException("Too many manifest entries")
        val entries = LinkedHashMap<String, Metadata>()
        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            if (fields.size != 3) throw IOException("Malformed backup manifest entry")
            val path = try {
                String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                throw IOException("Malformed backup path encoding", error)
            }
            safePath(path)
            val size = fields[1].toLongOrNull()?.takeIf { it in 0..MAX_ENTRY_BYTES }
                ?: throw IOException("Invalid backup entry size")
            if (!shaPattern.matches(fields[2])) throw IOException("Invalid backup checksum")
            if (entries.put(path, Metadata(size, fields[2])) != null) throw IOException("Duplicate manifest entry")
        }
        if (MANIFEST in entries) throw IOException("Reserved backup path in manifest")
        return timestamp to entries
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, expected: Long) {
        var copied = 0L
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > MAX_ENTRY_BYTES || copied > expected) throw IOException("Backup source changed while archiving")
            output.write(buffer, 0, count)
        }
        if (copied != expected) throw IOException("Backup source changed while archiving")
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > limit) throw IOException("Backup manifest exceeds size limit")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun checkedTotal(current: Long, added: Long): Long {
        if (added < 0 || current > MAX_TOTAL_BYTES - added) throw IOException("Backup payload exceeds size limit")
        return current + added
    }

    private fun safePath(path: String): String {
        try {
            validatePath(path)
        } catch (error: IllegalArgumentException) {
            throw IOException("Unsafe backup path", error)
        }
        return path
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank()) { "Empty backup path" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Absolute backup path" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path) && !path.contains(':')) { "Invalid backup path" }
        require(path.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_BYTES) { "Backup path is too long" }
        require(path.none { it.code < 0x20 || it.code == 0x7f }) { "Control character in backup path" }
        require(!path.endsWith('/') && !path.contains('\\')) { "Invalid path separator" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe backup path" }
        require(segments.all { it.length <= MAX_SEGMENT }) { "Backup path segment is too long" }
    }
}
