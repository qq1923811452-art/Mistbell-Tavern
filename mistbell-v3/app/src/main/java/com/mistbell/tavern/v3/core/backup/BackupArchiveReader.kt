package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.util.zip.ZipFile

data class BackupPreview(
    val manifest: BackupManifest,
    val archiveBytes: Long,
    val archiveSha256: String,
)

data class ExtractedBackup(
    val manifest: BackupManifest,
    val stagingDirectory: File,
    val archiveBytes: Long,
    val archiveSha256: String,
)

/** Validates metadata and every uncompressed byte before the caller can swap live data. */
class BackupArchiveReader {
    fun preview(archive: File): BackupPreview {
        require(archive.isFile && archive.canRead()) { "Backup archive cannot be read." }
        require(archive.length() <= BackupValidator.MAX_ARCHIVE_BYTES) { "Backup archive exceeds 512 MiB." }
        ZipFile(archive).use { zip ->
            val first = zip.entries().asSequence().firstOrNull()
                ?: throw IllegalArgumentException("Backup archive is empty.")
            require(first.name == BackupArchiveWriter.MANIFEST_PATH && !first.isDirectory) {
                "Backup manifest must be the first ZIP entry."
            }
            val manifestText = zip.getInputStream(first).use { input ->
                val bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1)
                require(bytes.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large." }
                bytes.toString(Charsets.UTF_8)
            }
            val manifest = BackupManifest.parse(manifestText)
            BackupValidator.validateManifest(manifest)
            val zipEntries = zip.entries().asSequence().toList()
            require(zipEntries.size == manifest.entryCount + 1) { "ZIP entry count does not match the manifest." }
            val names = zipEntries.drop(1).map { it.name }
            require(names.distinct().size == names.size) { "Backup ZIP contains duplicate paths." }
            require(names.toSet() == manifest.entries.map(BackupEntry::path).toSet()) {
                "ZIP entries do not match the manifest."
            }
            return BackupPreview(manifest, archive.length(), BackupValidator.sha256(archive))
        }
    }

    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    fun extract(archive: File, stagingDirectory: File): ExtractedBackup {
        val preview = preview(archive)
        require(!stagingDirectory.exists() || stagingDirectory.deleteRecursively()) {
            "Restore staging directory could not be cleared."
        }
        require(stagingDirectory.mkdirs()) { "Restore staging directory could not be created." }
        try {
            ZipFile(archive).use { zip ->
                val expected = preview.manifest.entries.associateBy(BackupEntry::path)
                var total = 0L
                val entries = zip.entries().asSequence().drop(1).toList()
                entries.forEach { zipped ->
                    val path = zipped.name
                    BackupValidator.validatePath(path)
                    require(!zipped.isDirectory) { "Directory entries are not supported in backup archives." }
                    val expectedEntry = expected[path] ?: throw IllegalArgumentException("Unexpected backup entry.")
                    val target = File(stagingDirectory, path.removePrefix("data/"))
                    require(target.canonicalPath.startsWith(stagingDirectory.canonicalPath + File.separator)) {
                        "Backup path escapes the staging directory."
                    }
                    target.parentFile?.let { parent ->
                        require(parent.isDirectory || parent.mkdirs()) {
                            "Restore staging directory could not be created."
                        }
                    }
                    var size = 0L
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(zipped).use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                total += count
                                require(size <= BackupValidator.MAX_ENTRY_BYTES) {
                                    "Backup entry exceeds the size limit."
                                }
                                require(total <= BackupValidator.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                    "Backup uncompressed size exceeds the limit."
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                    require(size == expectedEntry.size && hash == expectedEntry.sha256) {
                        "Backup entry integrity check failed: $path"
                    }
                }
                require(total == preview.manifest.totalBytes) { "Backup total size does not match." }
            }
            return ExtractedBackup(preview.manifest, stagingDirectory, preview.archiveBytes, preview.archiveSha256)
        } catch (failure: Exception) {
            stagingDirectory.deleteRecursively()
            throw failure
        }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    }
}
