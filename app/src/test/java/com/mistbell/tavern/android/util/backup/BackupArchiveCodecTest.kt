package com.mistbell.tavern.android.util.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveCodecTest {
    @Test
    fun roundTripsEntriesAndTimestamp() {
        val source =
            linkedMapOf(
                "database/tavern.db" to byteArrayOf(0, 1, 2, 3),
                "characters/card.json" to """{"name":"Ariya"}""".toByteArray(StandardCharsets.UTF_8),
            )

        val restored = BackupArchiveCodec.read(BackupArchiveCodec.write(source, 1_792_000_000_000L))

        assertEquals(1_792_000_000_000L, restored.createdAtEpochMs)
        assertEquals(source.keys, restored.entries.keys)
        source.forEach { (path, bytes) -> assertArrayEquals(bytes, restored.entries.getValue(path)) }
    }

    @Test
    fun rejectsPathTraversalBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.write(mapOf("../outside.txt" to byteArrayOf(1)), 1L)
        }
    }

    @Test
    fun rejectsAbsoluteBackslashAndDirectoryPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.write(mapOf("/outside.txt" to byteArrayOf(1)), 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.write(mapOf("folder\\outside.txt" to byteArrayOf(1)), 1L)
        }
    }

    @Test
    fun rejectsDriveQualifiedAndPlatformSpecificPaths() {
        listOf("C:/outside.txt", "folder/name:stream", "folder/line\u0007break").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupArchiveCodec.write(mapOf(path to byteArrayOf(1)), 1L)
            }
        }
    }

    @Test
    fun rejectsPathsWhoseUtf8EncodingExceedsThePathLimit() {
        val multibytePath = "${"界".repeat(400)}.txt"
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.write(mapOf(multibytePath to byteArrayOf(1)), 1L)
        }
    }

    @Test
    fun rejectsDirectoryStylePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.write(mapOf("folder/" to byteArrayOf(1)), 1L)
        }
    }

    @Test
    fun rejectsArchiveWithoutManifest() {
        val bytes =
            ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("database/tavern.db"))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }.toByteArray()

        assertThrows(IOException::class.java) { BackupArchiveCodec.read(bytes) }
    }

    @Test
    fun rejectsNegativeManifestTimestamp() {
        val archive = zipArchive("mistbell-backup\t1\t-1\n", emptyMap())

        assertThrows(IOException::class.java) { BackupArchiveCodec.read(archive) }
    }

    @Test
    fun rejectsChecksumMismatch() {
        val path = "characters/card.json"
        val payload = """{"name":"Ariya"}""".toByteArray(StandardCharsets.UTF_8)
        val encodedPath =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
        val manifest = "mistbell-backup\t1\t1\n$encodedPath\t${payload.size}\t${"0".repeat(64)}\n"

        assertThrows(IOException::class.java) {
            BackupArchiveCodec.read(zipArchive(manifest, mapOf(path to payload)))
        }
    }

    @Test
    fun rejectsManifestContentMismatch() {
        val missingPath = "database/missing.db"
        val encodedPath =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(missingPath.toByteArray(StandardCharsets.UTF_8))
        val manifest = "mistbell-backup\t1\t1\n$encodedPath\t1\t${"0".repeat(64)}\n"

        assertThrows(IOException::class.java) {
            BackupArchiveCodec.read(zipArchive(manifest, mapOf("database/other.db" to byteArrayOf(1))))
        }
    }

    private fun zipArchive(
        manifest: String,
        entries: Map<String, ByteArray>,
    ): ByteArray {
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/mistbell-backup-manifest.tsv"))
                zip.write(manifest.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                entries.forEach { (path, payload) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(IOException::class.java) { BackupArchiveCodec.read(byteArrayOf()) }
    }
}
