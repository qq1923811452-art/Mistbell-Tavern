package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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
    fun write(
        destination: File,
        files: Map<String, File>,
        appVersion: String,
        createdAt: String = Instant.now().toString(),
    ): BackupArchiveResult {
        val manifest = createManifest(files, appVersion, createdAt)
        prepareDestinationParent(destination)
        val partial = File(destination.parentFile ?: File("."), destination.name + ".partial")
        try {
            writeZip(partial, files, manifest)
            finalizeArchive(partial, destination)
            return BackupArchiveResult(destination, manifest, BackupValidator.sha256(destination))
        } finally {
            partial.delete()
        }
    }

    private fun createManifest(
        files: Map<String, File>,
        appVersion: String,
        createdAt: String,
    ): BackupManifest {
        require(files.isNotEmpty()) { "There is no user data to back up." }
        require(files.size <= BackupValidator.MAX_ENTRIES) { "Backup entry count exceeds the limit." }
        val entries = files.entries.sortedBy { it.key }.map { (path, file) -> createEntry(path, file) }
        val totalBytes = entries.fold(0L) { total, entry -> Math.addExact(total, entry.size) }
        require(totalBytes <= BackupValidator.MAX_TOTAL_UNCOMPRESSED_BYTES) {
            "Backup uncompressed size exceeds the limit."
        }
        return BackupManifest(
            version = BackupManifest.CURRENT_VERSION,
            createdAt = createdAt,
            appVersion = appVersion,
            entryCount = entries.size,
            totalBytes = totalBytes,
            entries = entries,
        ).also(BackupValidator::validateManifest)
    }

    private fun createEntry(path: String, file: File): BackupEntry {
        BackupValidator.validatePath(path)
        require(file.isFile && file.canRead()) { "Backup source is not a readable file." }
        require(file.length() <= BackupValidator.MAX_ENTRY_BYTES) { "Backup entry exceeds the size limit." }
        return BackupEntry(path, file.length(), BackupValidator.sha256(file))
    }

    private fun prepareDestinationParent(destination: File) {
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Backup destination directory could not be created.")
            }
        }
    }

    private fun writeZip(
        partial: File,
        files: Map<String, File>,
        manifest: BackupManifest,
    ) {
        ZipOutputStream(FileOutputStream(partial).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            manifest.entries.forEach { entry -> writeEntry(zip, files.getValue(entry.path), entry) }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, file: File, entry: BackupEntry) {
        zip.putNextEntry(ZipEntry(entry.path))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                if (written > entry.size || written > BackupValidator.MAX_ENTRY_BYTES) {
                    throw IOException("Backup source changed while being archived.")
                }
                digest.update(buffer, 0, count)
                zip.write(buffer, 0, count)
            }
            if (written != entry.size) throw IOException("Backup source changed while being archived.")
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(entry.sha256, ignoreCase = true)) {
            throw IOException("Backup source changed while being archived.")
        }
        zip.closeEntry()
    }

    private fun finalizeArchive(partial: File, destination: File) {
        require(partial.length() <= BackupValidator.MAX_ARCHIVE_BYTES) {
            "Backup archive exceeds 512 MiB."
        }
        val previous = File(destination.path + ".bak")
        previous.delete()
        val hadPrevious = destination.exists()
        if (hadPrevious && !destination.renameTo(previous)) {
            throw IOException("Existing backup could not be replaced.")
        }
        if (!partial.renameTo(destination)) {
            if (hadPrevious) previous.renameTo(destination)
            throw IOException("Backup archive could not be finalized.")
        }
        previous.delete()
    }

    companion object {
        const val MANIFEST_PATH = "manifest.json"
    }
}
