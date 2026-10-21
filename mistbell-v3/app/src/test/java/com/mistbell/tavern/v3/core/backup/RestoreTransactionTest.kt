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
    fun rollsBackEarlierFileSwapsWhenCommitCallbackFails() {
        val root = Files.createTempDirectory("restore-transaction-test").toFile()
        try {
            val live = File(root, "live.txt").apply { writeText("old") }
            val staged = File(root, "staged.txt").apply { writeText("new") }
            assertThrows(IllegalStateException::class.java) {
                RestoreTransaction().replaceFiles(mapOf(staged to live)) {
                    throw IllegalStateException("simulated commit failure")
                }
            }
            assertEquals("old", live.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rollsBackDatabaseSidecarCreatedDuringFailedReopen() {
        val root = Files.createTempDirectory("restore-transaction-test").toFile()
        try {
            val database = File(root, "chat.db").apply { writeText("old-db") }
            val stagedDatabase = File(root, "staged.db").apply { writeText("new-db") }
            val wal = File(root, "chat.db-wal").apply { writeText("old-wal") }

            assertThrows(IllegalStateException::class.java) {
                RestoreTransaction().replaceFiles(
                    stagedToDestination = mapOf(stagedDatabase to database),
                    removeDestinations = setOf(wal),
                    beforeCommit = {
                        wal.writeText("new-wal")
                        throw IllegalStateException("simulated reopen failure")
                    },
                )
            }

            assertEquals("old-db", database.readText())
            assertEquals("old-wal", wal.readText())
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
