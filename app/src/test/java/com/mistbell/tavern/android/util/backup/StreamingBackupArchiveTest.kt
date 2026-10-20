package com.mistbell.tavern.android.util.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory

class StreamingBackupArchiveTest {
    @Test
    fun streamsFilesAndRestoresVerifiedPayloads() {
        val root = createTempDirectory(prefix = "mistbell-stream-src")
        val stage = File(root, "stage")
        try {
            val database = File(root, "database.db").apply { writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() }) }
            val card = File(root, "card.json").apply { writeText("""{"name":"Ariya"}""", StandardCharsets.UTF_8) }
            val archive = ByteArrayOutputStream()
            StreamingBackupArchive.write(archive, mapOf("db/database.db" to database, "cards/card.json" to card), 1234L)

            val restored = StreamingBackupArchive.restore(ByteArrayInputStream(archive.toByteArray()), stage)

            assertEquals(1234L, restored.createdAtEpochMs)
            assertEquals(setOf("db/database.db", "cards/card.json"), restored.stagedFiles.keys)
            assertTrue(restored.stagedFiles.getValue("db/database.db").readBytes().contentEquals(database.readBytes()))
            assertEquals(card.readText(StandardCharsets.UTF_8), restored.stagedFiles.getValue("cards/card.json").readText(StandardCharsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedValidationDeletesStagingTree() {
        val root = createTempDirectory(prefix = "mistbell-stream-fail")
        val source = File(root, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val archive = ByteArrayOutputStream()
        StreamingBackupArchive.write(archive, mapOf("data.bin" to source), 1L)
        val stage = File(root, "stage")
        try {
            val damaged = archive.toByteArray().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 0x40).toByte() }
            try {
                StreamingBackupArchive.restore(ByteArrayInputStream(damaged), stage)
            } catch (_: IOException) {
                // Corrupt ZIP data must not leave partially restored files behind.
            }
            assertFalse("failed restore should remove staging directory", stage.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalPathsWhenWriting() {
        val root = createTempDirectory(prefix = "mistbell-stream-path")
        val source = File(root, "source.bin").apply { writeBytes(byteArrayOf(1)) }
        try {
            try {
                StreamingBackupArchive.write(ByteArrayOutputStream(), mapOf("../escape.bin" to source), 1L)
                throw AssertionError("Expected unsafe path to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
