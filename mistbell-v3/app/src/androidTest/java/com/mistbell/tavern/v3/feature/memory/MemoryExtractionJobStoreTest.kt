package com.mistbell.tavern.v3.feature.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryExtractionJobStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun atomicallyStoredQueueRoundTripsAcrossStoreInstances() {
        val fileName = testFileName()
        try {
            val job = job()
            MemoryExtractionJobStore(context, fileName).save(listOf(job))

            val loaded = MemoryExtractionJobStore(context, fileName).load()

            assertTrue(loaded is MemoryExtractionJobLoadResult.Loaded)
            assertEquals(listOf(job), (loaded as MemoryExtractionJobLoadResult.Loaded).jobs)
        } finally {
            removeTestFiles(fileName)
        }
    }

    @Test fun corruptedQueueIsReportedInsteadOfSilentlyReset() {
        val fileName = testFileName()
        try {
            MemoryExtractionJobStore(context, fileName).save(listOf(job()))
            File(context.filesDir, fileName).writeBytes("not-a-valid-queue".toByteArray())

            val store = MemoryExtractionJobStore(context, fileName)
            val loaded = store.load()

            assertTrue(loaded is MemoryExtractionJobLoadResult.Corrupt)
            assertTrue(!File(context.filesDir, fileName).exists())
            assertTrue(
                context.filesDir.listFiles()?.any { it.name.startsWith("$fileName.corrupt-") } == true,
            )
            assertTrue(store.load() is MemoryExtractionJobLoadResult.Missing)
        } finally {
            removeTestFiles(fileName)
        }
    }

    private fun testFileName() = "memory-queue-test-${UUID.randomUUID()}.bin"

    private fun removeTestFiles(fileName: String) {
        File(context.filesDir, fileName).delete()
        File(context.filesDir, "$fileName.bak").delete()
        File(context.filesDir, "$fileName.new").delete()
        context.filesDir.listFiles()?.filter { it.name.startsWith("$fileName.corrupt-") }?.forEach(File::delete)
    }

    private fun job() = MemoryExtractionJob(
        id = "job-${UUID.randomUUID()}",
        conversationId = "conversation-1",
        turnIds = listOf("turn-1", "turn-2", "turn-3"),
        providerProfileId = "memory-profile",
        credentialHandle = "opaque-credential-handle",
        createdAtEpochMillis = 100L,
    )
}
