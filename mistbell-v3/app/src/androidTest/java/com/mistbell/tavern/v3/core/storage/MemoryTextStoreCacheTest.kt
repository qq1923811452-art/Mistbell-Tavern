package com.mistbell.tavern.v3.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A retrieval turn reads the source-text store twice (staleness check, then injection). These
 * tests pin the cache's two safety properties: a hit must not skip a real write, and a corrupt
 * file must never be remembered as valid.
 */
@RunWith(AndroidJUnit4::class)
class MemoryTextStoreCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var fileName: String

    @Before
    fun setUp() {
        // A unique name per test keeps the process-wide cache from leaking between tests
        // without reaching into the cache's internal API.
        fileName = "memory-text-cache-${UUID.randomUUID()}.bin"
    }

    @Test
    fun repeatedLoadsShareOneDecodedList() {
        val store = AtomicMemoryTextStore(context, fileName)
        val twin = AtomicMemoryTextStore(context, fileName)
        try {
            store.save(listOf(record("a", "alpha"), record("b", "beta")))

            val first = store.load()
            val second = twin.load()

            assertTrue(first is MemoryTextLoadResult.Loaded)
            assertTrue(second is MemoryTextLoadResult.Loaded)
            assertSame(
                (first as MemoryTextLoadResult.Loaded).records,
                (second as MemoryTextLoadResult.Loaded).records,
            )
        } finally {
            deleteFiles()
        }
    }

    @Test
    fun saveThroughAnotherInstanceInvalidatesTheCache() {
        val store = AtomicMemoryTextStore(context, fileName)
        val twin = AtomicMemoryTextStore(context, fileName)
        try {
            store.save(listOf(record("a", "alpha")))
            assertTrue(store.load() is MemoryTextLoadResult.Loaded)

            twin.save(listOf(record("a", "alpha"), record("b", "beta")))

            val reloaded = store.load()
            assertTrue(reloaded is MemoryTextLoadResult.Loaded)
            assertEquals(2, (reloaded as MemoryTextLoadResult.Loaded).records.size)
        } finally {
            deleteFiles()
        }
    }

    @Test
    fun sameLengthRewriteIsNotServedFromCache() {
        val store = AtomicMemoryTextStore(context, fileName)
        try {
            store.save(listOf(record("a", "alpha")))
            assertTrue(store.load() is MemoryTextLoadResult.Loaded)

            // Identical byte length, written immediately: only the write generation can catch this.
            store.save(listOf(record("a", "ALPHA")))

            val reloaded = store.load()
            assertTrue(reloaded is MemoryTextLoadResult.Loaded)
            assertEquals("ALPHA", (reloaded as MemoryTextLoadResult.Loaded).records.single().text)
        } finally {
            deleteFiles()
        }
    }

    @Test
    fun corruptStoreIsNeverCachedAndRecoversAfterRewrite() {
        val store = AtomicMemoryTextStore(context, fileName)
        val file = File(context.filesDir, fileName)
        try {
            store.save(listOf(record("a", "alpha")))
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

            assertTrue(store.load() is MemoryTextLoadResult.Corrupt)
            assertTrue(store.load() is MemoryTextLoadResult.Corrupt)

            store.save(listOf(record("z", "zeta")))
            val recovered = store.load()
            assertTrue(recovered is MemoryTextLoadResult.Loaded)
            assertEquals("z", (recovered as MemoryTextLoadResult.Loaded).records.single().id)
        } finally {
            deleteFiles()
        }
    }

    @Test
    fun updateIsVisibleToOtherInstances() {
        val store = AtomicMemoryTextStore(context, fileName)
        val twin = AtomicMemoryTextStore(context, fileName)
        try {
            store.save(listOf(record("a", "alpha")))
            assertTrue(store.load() is MemoryTextLoadResult.Loaded)

            val updated = twin.update { current -> current + record("b", "beta") }

            assertEquals(2, updated.size)
            val reloaded = store.load()
            assertTrue(reloaded is MemoryTextLoadResult.Loaded)
            assertEquals(listOf("a", "b"), (reloaded as MemoryTextLoadResult.Loaded).records.map { it.id })
        } finally {
            deleteFiles()
        }
    }

    private fun record(id: String, text: String) = MemoryTextRecord(id = id, text = text)

    private fun deleteFiles() {
        val base = File(context.filesDir, fileName)
        base.delete()
        File(base.path + ".bak").delete()
        File(base.path + ".new").delete()
    }
}
