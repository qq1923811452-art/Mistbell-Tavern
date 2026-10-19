package com.mistbell.tavern.android.util.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveCodecTest {
    @Test
    fun roundTripsEntriesAndTimestamp() {
        val source = linkedMapOf(
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
    fun rejectsAbsoluteAndBackslashPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.write(mapOf("/outside.txt" to byteArrayOf(1)), 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.write(mapOf("folder\\outside.txt" to byteArrayOf(1)), 1L)
        }
    }

    @Test
    fun rejectsArchiveWithoutManifest() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("database/tavern.db"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()

        assertThrows(IOException::class.java) { BackupArchiveCodec.read(bytes) }
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(IOException::class.java) { BackupArchiveCodec.read(byteArrayOf()) }
    }
}
