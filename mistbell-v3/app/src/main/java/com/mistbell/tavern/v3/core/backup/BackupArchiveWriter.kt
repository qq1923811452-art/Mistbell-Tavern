package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BackupArchiveResult(
    val file: File,
    val manifest: BackupManifest,
    val archiveSha256: String,
)

/** Streaming ZIP writer. Input files are hashed before writing so the manifest can be first. */
class BackupArchiveWriter {
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount")
    fun write(
        destination: File,
        files: Map<String, File>,
        appVersion: String,
        createdAt: String = Instant.now().toString(),
    ): BackupArchiveResult {
        require(files.isNotEmpty()) { "There is no user data to back up." }
        require(files.size <= BackupValidator.MAX_ENTRIES) { "Backup entry count exceeds the limit." }
        val entries = files.entries.sortedBy(Map.Entry<String, File>::key).map { (path, file) ->
            BackupValidator.validatePath(path)
            require(file.isFile && file.canRead()) { "Backup source is not a readable file." }
            require(file.length() <= BackupValidator.MAX_ENTRY_BYTES) { "Backup entry exceeds the size limit." }
            BackupEntry(path, file.length(), BackupValidator.sha256(file))
        }
        val totalBytes = entries.fold(0L) { total, entry -> Math.addExact(total, entry.size) }
        require(totalBytes <= BackupValidator.MAX_TOTAL_UNCOMPRESSED_BYTES) {
            "Backup uncompressed size exceeds the limit."
        }
        val manifest = BackupManifest(
            version = BackupManifest.CURRENT_VERSION,
            createdAt = createdAt,
            appVersion = appVersion,
            entryCount = entries.size,
            totalBytes = totalBytes,
            entries = entries,
        )
        BackupValidator.validateManifest(manifest)
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Backup destination directory could not be created.")
            }
        }
        val partial = File(destination.parentFile ?: File("."), destination.name + ".partial")
        try {
            ZipOutputStream(FileOutputStream(partial).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_PATH))
                zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                entries.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.path))
                    files.getValue(entry.path).inputStream().buffered().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > entry.size || written > BackupValidator.MAX_ENTRY_BYTES) {
                                throw IOException("Backup source changed while being archived.")
                            }
                            zip.write(buffer, 0, count)
                        }
                        if (written != entry.size) throw IOException("Backup source changed while being archived.")
                    }
                    zip.closeEntry()
                }
            }
            require(partial.length() <= BackupValidator.MAX_ARCHIVE_BYTES) { "Backup archive exceeds 512 MiB." }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Existing backup could not be replaced.")
            }
            if (!partial.renameTo(destination)) throw IOException("Backup archive could not be finalized.")
            return BackupArchiveResult(destination, manifest, BackupValidator.sha256(destination))
        } catch (failure: Exception) {
            partial.delete()
            throw failure
        }
    }

    companion object {
        const val MANIFEST_PATH = "manifest.json"
    }
}
