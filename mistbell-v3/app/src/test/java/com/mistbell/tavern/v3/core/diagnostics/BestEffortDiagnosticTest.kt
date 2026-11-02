package com.mistbell.tavern.v3.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class BestEffortDiagnosticTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successfulDiagnosticIsRecorded() {
        val directory = temporaryFolder.newFolder()
        val store = DiagnosticLogStore(directory) { Instant.EPOCH }

        bestEffortDiagnostic { store.info("test.event", "detail") }

        assertEquals(
            "1970-01-01T00:00:00Z [INFO] test.event - detail\n",
            directory.resolve(DiagnosticLogStore.LOG_FILE_NAME).readText(),
        )
    }

    @Test
    fun everyLevelIsBestEffortWhenDirectoryIsAFile() {
        val store = DiagnosticLogStore(temporaryFolder.newFile())
        var completed = 0

        bestEffortDiagnostic { store.info("test.info") }
        completed += 1
        bestEffortDiagnostic { store.warn("test.warn") }
        completed += 1
        bestEffortDiagnostic { store.error("test.error") }
        completed += 1

        assertEquals(3, completed)
    }

    @Test
    fun clockFailureDoesNotEscape() {
        val store = DiagnosticLogStore(temporaryFolder.newFolder()) { throw IOException("Clock unavailable") }

        bestEffortDiagnostic { store.info("test.event") }
    }

    @Test
    fun detailConstructionFailureDoesNotEscape() {
        val store = DiagnosticLogStore(temporaryFolder.newFolder())
        val detail: () -> String = { throw IllegalStateException("Diagnostic detail unavailable") }

        bestEffortDiagnostic { store.warn("test.event", detail()) }
    }
}
