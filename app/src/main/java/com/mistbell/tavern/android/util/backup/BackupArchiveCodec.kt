package com.mistbell.tavern.android.util.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Validates and encodes the portable archive format used by full backup/restore.
 *
 * This codec intentionally returns validated entry bytes instead of extracting paths itself.
 * The restore layer must stage the returned entries and commit them only after validation.
 */
object BackupArchiveCodec {
    private const val MANIFEST_PATH = "META-INF/mistbell-backup-manifest.tsv"
    private const val MANIFEST_HEADER = "mistbell-backup\t1"
    const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 128 * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 10_000

    data class Archive(
        val createdAtEpochMs: Long,
        val entries: Map<String, ByteArray>,
    )

    @Throws(IOException::class)
    fun write(
        entries: Map<String, ByteArray>,
        createdAtEpochMs: Long,
    ): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "Too many backup entries" }
        require(createdAtEpochMs >= 0) { "Invalid backup timestamp" }

        val sorted = entries.toSortedMap()
        var totalBytes = 0L
        sorted.forEach { (path, bytes) ->
            validatePath(path)
            require(path != MANIFEST_PATH) { "Reserved backup path" }
            require(bytes.size <= MAX_ENTRY_BYTES) { "Backup entry is too large: $path" }
            totalBytes += bytes.size
            require(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Backup payload is too large" }
        }

        val manifest = buildString {
            append(MANIFEST_HEADER).append('\t').append(createdAtEpochMs).append('\n')
            sorted.forEach { (path, bytes) ->
                val encodedPath = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
                append(encodedPath).append('\t')
                    .append(bytes.size).append('\t')
                    .append(sha256(bytes)).append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(manifest)
            zip.closeEntry()
            sorted.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val result = output.toByteArray()
        require(result.size <= MAX_ARCHIVE_BYTES) { "Backup archive is too large" }
        return result
    }

    @Throws(IOException::class)
    fun read(bytes: ByteArray): Archive {
        if (bytes.isEmpty() || bytes.size > MAX_ARCHIVE_BYTES) {
            throw IOException("Backup archive size is invalid")
        }

        val rawEntries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                try {
                    validatePath(path)
                } catch (error: IllegalArgumentException) {
                    throw IOException("Unsafe backup path", error)
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (rawEntries.containsKey(path)) throw IOException("Duplicate backup entry: $path")
                if (rawEntries.size >= MAX_ENTRIES + 1) throw IOException("Too many backup entries")

                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0L
                while (true) {
                    val count = zip.read(chunk)
                    if (count < 0) break
                    entryBytes += count
                    totalBytes += count
                    if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw IOException("Backup payload exceeds size limits")
                    }
                    buffer.write(chunk, 0, count)
                }
                rawEntries[path] = buffer.toByteArray()
                zip.closeEntry()
            }
        }

        val manifest = rawEntries.remove(MANIFEST_PATH)
            ?: throw IOException("Backup manifest is missing")
        val lines = manifest.toString(StandardCharsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
        val header = lines.firstOrNull()?.split('\t')
        if (header == null || header.size != 3 || header[0] != "mistbell-backup" ||
            header[1] != "1" || header[2].toLongOrNull() == null
        ) {
            throw IOException("Unsupported or malformed backup manifest")
        }
        val expected = LinkedHashMap<String, Pair<Long, String>>()
        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            if (fields.size != 3) throw IOException("Malformed backup manifest entry")
            val path = try {
                String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                throw IOException("Malformed backup path encoding", error)
            }
            try {
                validatePath(path)
            } catch (error: IllegalArgumentException) {
                throw IOException("Unsafe path in backup manifest", error)
            }
            val size = fields[1].toLongOrNull() ?: throw IOException("Malformed backup entry size")
            val digest = fields[2]
            if (size < 0 || !digest.matches(Regex("[0-9a-f]{64}")) || expected.put(path, size to digest) != null) {
                throw IOException("Invalid or duplicate backup manifest entry")
            }
        }

        if (expected.keys != rawEntries.keys) throw IOException("Backup contents do not match manifest")
        rawEntries.forEach { (path, payload) ->
            val metadata = expected[path] ?: throw IOException("Unlisted backup entry")
            if (metadata.first != payload.size.toLong() || metadata.second != sha256(payload)) {
                throw IOException("Backup checksum mismatch: $path")
            }
        }
        return Archive(header[2].toLong(), rawEntries)
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank()) { "Empty backup path" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Absolute backup path" }
        require(!path.contains('\\')) { "Backslashes are not valid archive path separators" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe backup path" }
        require(!path.contains('\u0000')) { "NUL in backup path" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
