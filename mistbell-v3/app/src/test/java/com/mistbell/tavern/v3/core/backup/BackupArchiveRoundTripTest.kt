package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveRoundTripTest {
    @Test
    fun archiveWritesManifestFirstAndRoundTripsHashAndBytes() {
        val root = Files.createTempDirectory("backup-round-trip").toFile()
        try {
            val source = File(root, "settings.json").apply { writeText("{\"theme\":\"dark\"}") }
            val archive = File(root, "backup.zip")
            val result = BackupArchiveWriter().write(
                destination = archive,
                files = mapOf("data/settings.json" to source),
                appVersion = "3.0.0-dev",
                createdAt = "2026-10-21T12:00:00Z",
            )

            val preview = BackupArchiveReader().preview(archive)
            assertEquals("manifest.json", java.util.zip.ZipFile(archive).use { it.entries().nextElement().name })
            assertEquals(1, preview.manifest.entryCount)
            assertEquals(result.archiveSha256, preview.archiveSha256)
            assertEquals(BackupValidator.sha256(source), preview.manifest.entries.single().sha256)

            val staging = File(root, "restore-tmp")
            val extracted = BackupArchiveReader().extract(archive, staging)
            assertEquals(result.manifest, extracted.manifest)
            assertEquals(source.readText(), File(staging, "settings.json").readText())
            assertTrue(extracted.archiveSha256.matches(Regex("[a-f0-9]{64}")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun damagedEntryIsRejectedBeforeItCanBeInstalled() {
        val root = Files.createTempDirectory("backup-corrupt-entry").toFile()
        try {
            val source = File(root, "settings.json").apply { writeText("original") }
            val goodArchive = File(root, "good.zip")
            val result = BackupArchiveWriter().write(
                goodArchive,
                mapOf("data/settings.json" to source),
                "3.0.0-dev",
            )
            val corrupted = File(root, "corrupted.zip")
            ZipOutputStream(FileOutputStream(corrupted)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(result.manifest.toJson().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/settings.json"))
                zip.write("tampered".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            assertNotEquals(BackupValidator.sha256(goodArchive), BackupValidator.sha256(corrupted))
            val staging = File(root, "restore-tmp")
            assertThrows(IllegalArgumentException::class.java) {
                BackupArchiveReader().extract(corrupted, staging)
            }
            assertFalse(staging.exists())
            assertEquals("original", source.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
