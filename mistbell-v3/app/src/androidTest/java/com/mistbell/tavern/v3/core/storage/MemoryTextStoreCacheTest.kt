package com.mistbell.tavern.v3.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

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

    @Test
    fun backupRecoveryRunsBeforeACacheHit() {
        val store = AtomicMemoryTextStore(context, fileName)
        val base = File(context.filesDir, fileName)
        try {
            store.save(listOf(record("a", "alpha")))
            val oldBytes = base.readBytes()
            store.save(listOf(record("a", "ALPHA")))
            assertTrue(store.load() is MemoryTextLoadResult.Loaded)
            File(base.path + ".bak").writeBytes(oldBytes)

            val recovered = store.load() as MemoryTextLoadResult.Loaded
            assertEquals("alpha", recovered.records.single().text)
            assertTrue(!File(base.path + ".bak").exists())
        } finally {
            deleteFiles()
        }
    }

    @Test
    fun sameMetadataReplacementDoesNotReuseTheOldInode() {
        val store = AtomicMemoryTextStore(context, fileName)
        val base = File(context.filesDir, fileName)
        val replacement = File(base.path + ".replacement")
        try {
            store.save(listOf(record("a", "alpha")))
            assertTrue(store.load() is MemoryTextLoadResult.Loaded)
            val modified = base.lastModified()
            replacement.writeBytes(com.mistbell.tavern.v3.core.memory.MemoryTextCodec.encode(listOf(record("a", "ALPHA"))))
            assertEquals(base.length(), replacement.length())
            assertTrue(replacement.setLastModified(modified))
            assertTrue(replacement.renameTo(base))

            val reloaded = store.load() as MemoryTextLoadResult.Loaded
            assertEquals("ALPHA", reloaded.records.single().text)
        } finally {
            replacement.delete()
            deleteFiles()
        }
    }

    @Test
    fun sourceUpdateWaitsUntilIndexCommitFinishes() {
        val store = AtomicMemoryTextStore(context, fileName)
        val expected = listOf(record("a", "alpha"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val attempted = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            store.save(expected)
            val commit =
                executor.submit {
                    store.commitIndexIfCurrent(expected) {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val update =
                executor.submit {
                    attempted.countDown()
                    store.update { it + record("b", "beta") }
                    finished.countDown()
                }
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(!finished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown()
            commit.get(5, java.util.concurrent.TimeUnit.SECONDS)
            update.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2, (store.load() as MemoryTextLoadResult.Loaded).records.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
            deleteFiles()
        }
    }

    @Test
    fun staleIndexCommitDoesNotResurrectDeletedSource() {
        val store = AtomicMemoryTextStore(context, fileName)
        val expected = listOf(record("a", "alpha"))
        try {
            store.save(expected)
            assertTrue(File(context.filesDir, fileName).delete())
            var committed = false
            val failure = runCatching { store.commitIndexIfCurrent(expected) { committed = true } }
            assertTrue(failure.isFailure)
            assertTrue(!committed)
            assertTrue(store.load() is MemoryTextLoadResult.Missing)
        } finally {
            deleteFiles()
        }
    }

    private fun record(
        id: String,
        text: String,
    ) = MemoryTextRecord(id = id, text = text)

    private fun deleteFiles() {
        val base = File(context.filesDir, fileName)
        base.delete()
        File(base.path + ".bak").delete()
        File(base.path + ".new").delete()
    }
}
