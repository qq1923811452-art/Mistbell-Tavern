package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RestoreTransactionTest {
    @Test
    fun replacesExistingFileAndKeepsOldValueWhenInstallFails() {
        val root = Files.createTempDirectory("restore-transaction-test").toFile()
        try {
            val old = File(root, "live.txt").apply { writeText("old") }
            val staged = File(root, "staged.txt").apply { writeText("new") }
            RestoreTransaction().replaceFiles(mapOf(staged to old))
            assertEquals("new", old.readText())

            val live2 = File(root, "live2.txt").apply { writeText("keep") }
            val missing = File(root, "missing.txt")
            assertThrows(IllegalArgumentException::class.java) {
                RestoreTransaction().replaceFiles(mapOf(missing to live2))
            }
            assertEquals("keep", live2.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateDestinationsBeforeMutation() {
        val root = Files.createTempDirectory("restore-transaction-test").toFile()
        try {
            val first = File(root, "first").apply { writeText("one") }
            val second = File(root, "second").apply { writeText("two") }
            val destination = File(root, "live").apply { writeText("original") }
            assertThrows(IllegalArgumentException::class.java) {
                RestoreTransaction().replaceFiles(mapOf(first to destination, second to destination))
            }
            assertEquals("original", destination.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
