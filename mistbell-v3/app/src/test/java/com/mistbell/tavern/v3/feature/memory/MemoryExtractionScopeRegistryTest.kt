package com.mistbell.tavern.v3.feature.memory

import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryExtractionScopeRegistryTest {
    @Test fun bindingsSurviveReopeningRegistryIncludingPendingState() {
        val directory = Files.createTempDirectory("extraction-scopes").toFile()
        val registry = AtomicMemoryExtractionScopeRegistry(directory, "queue")
        val binding = MemoryExtractionScopeBinding("legacy", "role", "history", true)
        registry.save(listOf(binding))
        assertEquals(listOf(binding), AtomicMemoryExtractionScopeRegistry(directory, "queue").load())
        registry.save(listOf(binding.copy(pending = false)))
        assertEquals(false, AtomicMemoryExtractionScopeRegistry(directory, "queue").load().single().pending)
    }

    @Test fun corruptRegistryFailsClosedInsteadOfDroppingTombstones() {
        val directory = Files.createTempDirectory("extraction-corrupt").toFile()
        directory.resolve("queue.scopes.json").writeText("corrupt")
        assertThrows(Exception::class.java) {
            AtomicMemoryExtractionScopeRegistry(directory, "queue").load()
        }
    }

    @Test fun oversizedBaseOrBackupRegistryFailsClosedBeforeReading() {
        val directory = Files.createTempDirectory("extraction-oversized").toFile()
        for (suffix in listOf("", ".bak")) {
            val oversized = directory.resolve("queue.scopes.json$suffix")
            RandomAccessFile(oversized, "rw").use {
                it.setLength(MemoryExtractionJobCodec.MAX_BYTES.toLong() + 1L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AtomicMemoryExtractionScopeRegistry(directory, "queue").load()
            }
            oversized.delete()
        }
    }

}
