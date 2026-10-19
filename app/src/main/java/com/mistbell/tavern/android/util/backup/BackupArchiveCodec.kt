@file:Suppress("ktlint:standard:multiline-expression-wrapping")

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
 * This bounded in-memory codec is for small archives and tests. Production backup/restore must use
 * streaming and staging rather than buffering a large database and all uncompressed entries at once.
 */
@Suppress("TooManyFunctions")
object BackupArchiveCodec {
    private const val MANIFEST_PATH = "META-INF/mistbell-backup-manifest.tsv"
    private const val MANIFEST_FORMAT = "mistbell-backup"
    private const val MANIFEST_VERSION = "1"
    private const val MANIFEST_FIELD_COUNT = 3
    private const val MANIFEST_ENTRY_COUNT = 1
    private const val SHA256_HEX_LENGTH = 64
    private const val MAX_PATH_LENGTH = 1024
    private const val MAX_PATH_SEGMENT_LENGTH = 255
    private const val MIN_TIMESTAMP = 0L
    private const val MIN_PRINTABLE_PATH_CHAR = 0x20
    private const val DELETE_CHAR = 0x7f
    private val SHA256_PATTERN = Regex("[0-9a-f]{$SHA256_HEX_LENGTH}")

    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024 * 1024
    const val MAX_ENTRIES = 10_000

    private const val MANIFEST_HEADER = "$MANIFEST_FORMAT\t$MANIFEST_VERSION"

    data class Archive(
        val createdAtEpochMs: Long,
        val entries: Map<String, ByteArray>,
    )

    private data class EntryMetadata(
        val size: Long,
        val sha256: String,
    )

    private data class ParsedManifest(
        val createdAtEpochMs: Long,
        val entries: Map<String, EntryMetadata>,
    )

    @Suppress("ThrowsCount")
    @Throws(IOException::class)
    fun write(
        entries: Map<String, ByteArray>,
        createdAtEpochMs: Long,
    ): ByteArray {
        if (entries.size + MANIFEST_ENTRY_COUNT > MAX_ENTRIES) {
            throw IOException("Too many backup entries including manifest")
        }
        require(createdAtEpochMs >= MIN_TIMESTAMP) { "Invalid backup timestamp" }

        val sorted = entries.toSortedMap()
        var totalBytes = 0L
        sorted.forEach { (path, bytes) ->
            validatePath(path)
            require(path != MANIFEST_PATH) { "Reserved backup path" }
            if (bytes.size > MAX_ENTRY_BYTES) {
                throw IOException("Backup entry is too large: $path")
            }
            totalBytes += bytes.size
            if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw IOException("Backup payload is too large")
            }
        }

        val manifest = buildString {
            append(MANIFEST_HEADER).append('\t').append(createdAtEpochMs).append('\n')
            sorted.forEach { (path, bytes) ->
                val encodedPath =
                    Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
                append(encodedPath).append('\t')
                    .append(bytes.size).append('\t')
                    .append(sha256(bytes)).append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)
        if (manifest.size > MAX_ENTRY_BYTES) {
            throw IOException("Backup manifest is too large")
        }

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
        if (result.size > MAX_ARCHIVE_BYTES) {
            throw IOException("Backup archive is too large")
        }
        return result
    }

    @Suppress("ThrowsCount")
    @Throws(IOException::class)
    fun read(bytes: ByteArray): Archive {
        if (bytes.isEmpty() || bytes.size > MAX_ARCHIVE_BYTES) {
            throw IOException("Backup archive size is invalid")
        }

        val rawEntries = readRawEntries(bytes)
        val manifestBytes = rawEntries.remove(MANIFEST_PATH)
            ?: throw IOException("Backup manifest is missing")
        val manifest = parseManifest(manifestBytes)
        verifyEntries(rawEntries, manifest.entries)
        return Archive(manifest.createdAtEpochMs, rawEntries)
    }

    @Suppress("NestedBlockDepth", "ThrowsCount")
    private fun readRawEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val rawEntries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = safeArchivePath(entry.name)
                if (!entry.isDirectory) {
                    if (rawEntries.containsKey(path)) {
                        throw IOException("Duplicate backup entry: $path")
                    }
                    if (rawEntries.size >= MAX_ENTRIES) {
                        throw IOException("Too many backup entries including manifest")
                    }
                    val payload = readEntry(zip)
                    totalBytes += payload.size
                    if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw IOException("Backup payload exceeds size limits")
                    }
                    rawEntries[path] = payload
                }
                zip.closeEntry()
            }
        }
        return rawEntries
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = zip.read(chunk)
            if (count < 0) break
            if (buffer.size().toLong() + count > MAX_ENTRY_BYTES) {
                throw IOException("Backup entry exceeds size limit")
            }
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }

    private fun parseManifest(bytes: ByteArray): ParsedManifest {
        val lines = bytes.toString(StandardCharsets.UTF_8).lineSequence()
            .filter { it.isNotEmpty() }
            .toList()
        val createdAt = parseManifestHeader(lines.firstOrNull())
        val entries = LinkedHashMap<String, EntryMetadata>()
        lines.drop(1).forEach { line ->
            val (path, metadata) = parseManifestEntry(line)
            if (entries.put(path, metadata) != null) {
                throw IOException("Duplicate backup manifest entry")
            }
        }
        return ParsedManifest(createdAt, entries)
    }

    @Suppress("ThrowsCount")
    private fun parseManifestHeader(line: String?): Long {
        val fields = line?.split('\t')
            ?: throw IOException("Backup manifest header is missing")
        if (fields.size != MANIFEST_FIELD_COUNT) {
            throw IOException("Unsupported or malformed backup manifest")
        }
        if (fields[0] != MANIFEST_FORMAT || fields[1] != MANIFEST_VERSION) {
            throw IOException("Unsupported or malformed backup manifest")
        }
        val createdAt = fields[2].toLongOrNull()
            ?: throw IOException("Invalid backup timestamp")
        if (createdAt < MIN_TIMESTAMP) throw IOException("Invalid backup timestamp")
        return createdAt
    }

    @Suppress("ThrowsCount")
    private fun parseManifestEntry(line: String): Pair<String, EntryMetadata> {
        val fields = line.split('\t')
        if (fields.size != MANIFEST_FIELD_COUNT) {
            throw IOException("Malformed backup manifest entry")
        }
        val decodedPath = try {
            String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IOException("Malformed backup path encoding", error)
        }
        val path = safeArchivePath(decodedPath)
        val size = fields[1].toLongOrNull()
            ?: throw IOException("Malformed backup entry size")
        val digest = fields[2]
        if (size < MIN_TIMESTAMP || !SHA256_PATTERN.matches(digest)) {
            throw IOException("Invalid backup manifest entry")
        }
        return path to EntryMetadata(size, digest)
    }

    @Suppress("ThrowsCount")
    private fun verifyEntries(
        actual: Map<String, ByteArray>,
        expected: Map<String, EntryMetadata>,
    ) {
        if (expected.keys != actual.keys) {
            throw IOException("Backup contents do not match manifest")
        }
        actual.forEach { (path, payload) ->
            val metadata = expected[path] ?: throw IOException("Unlisted backup entry")
            if (metadata.size != payload.size.toLong() || metadata.sha256 != sha256(payload)) {
                throw IOException("Backup checksum mismatch: $path")
            }
        }
    }

    private fun safeArchivePath(path: String): String {
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
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Drive-qualified backup path" }
        require(!path.contains(':')) { "Colon is not valid in backup paths" }
        require(path.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_LENGTH) {
            "Backup path is too long"
        }
        require(path.none { it.code < MIN_PRINTABLE_PATH_CHAR || it.code == DELETE_CHAR }) {
            "Control characters are not valid in backup paths"
        }
        require(!path.endsWith('/')) { "Directory paths are not valid backup entries" }
        require(!path.contains('\\')) { "Backslashes are not valid archive path separators" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe backup path" }
        require(segments.all { it.length <= MAX_PATH_SEGMENT_LENGTH }) { "Backup path segment is too long" }
        require(!path.contains('\u0000')) { "NUL in backup path" }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
