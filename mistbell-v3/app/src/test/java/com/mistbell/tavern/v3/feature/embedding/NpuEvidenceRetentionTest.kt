package com.mistbell.tavern.v3.feature.embedding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NpuEvidenceRetentionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun keepsOnlyFiveNewestEvidenceFilesAndTheirChecksums() {
        val directory = temporaryFolder.newFolder("npu-evidence")
        repeat(8) { index ->
            val evidence = File(directory, "npu-evidence-$index.json")
            evidence.writeText("{\"index\":$index}")
            evidence.setLastModified((index + 1L) * 1_000L)
            File(directory, evidence.name + ".sha256").writeText("checksum-$index")
        }
        val unrelated = File(directory, "keep-me.txt").apply { writeText("unrelated") }

        NpuEvidenceRetention.prune(directory)

        val evidenceFiles = directory.listFiles { file ->
            file.name.startsWith("npu-evidence-") && file.name.endsWith(".json")
        }.orEmpty()
        assertEquals(5, evidenceFiles.size)
        assertTrue(evidenceFiles.any { it.name == "npu-evidence-7.json" })
        assertTrue(evidenceFiles.none { it.name == "npu-evidence-0.json" })
        assertTrue(evidenceFiles.all { File(directory, it.name + ".sha256").isFile })
        assertTrue(unrelated.isFile)
    }
}
