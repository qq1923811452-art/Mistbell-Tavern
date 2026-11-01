package com.mistbell.tavern.v3.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.v3.core.memory.StoredVector
import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import com.mistbell.tavern.v3.core.memory.VectorSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MemoryPersistenceRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun malformedOverrideJsonIsReportedWithoutChangingTheFile() {
        val name = "override-regression-${UUID.randomUUID()}.json"
        val file = File(context.filesDir, name)
        try {
            file.writeText("{")
            val store = AtomicMemoryOverrideStore(context, name)
            assertTrue(store.load() is MemoryOverrideLoadResult.Corrupt)
            assertTrue(runCatching { store.update { emptyList() } }.isFailure)
            assertEquals("{", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun distinctStoreInstancesInvalidateCachedSnapshotEvenWithSameLengthAndMtime() {
        val name = "vector-regression-${UUID.randomUUID()}.bin"
        val file = File(context.filesDir, name)
        try {
            val first = AtomicVectorSnapshotStore(context, name)
            val second = AtomicVectorSnapshotStore(context, name)
            first.save(snapshot("first"))
            val initial = loaded(first)
            assertSame(initial, loaded(second))
            val modified = file.lastModified()
            val length = file.length()
            second.save(snapshot("other"))
            assertEquals(length, file.length())
            assertTrue(file.setLastModified(modified))
            val changed = loaded(first)
            assertNotSame(initial, changed)
            assertEquals("other", changed.records.single().id)
        } finally {
            VectorSnapshotMemoryCache.invalidate(file)
            file.delete()
        }
    }

    @Test
    fun atomicBackupRecoveryRunsBeforeAHitCanBeReturned() {
        val name = "vector-recovery-${UUID.randomUUID()}.bin"
        val file = File(context.filesDir, name)
        val backup = File(file.path + ".bak")
        try {
            val store = AtomicVectorSnapshotStore(context, name)
            store.save(snapshot("first"))
            loaded(store)
            backup.writeBytes(VectorSnapshotCodec.encode(snapshot("other")))
            assertEquals("other", loaded(store).records.single().id)
            assertTrue(!backup.exists())
        } finally {
            VectorSnapshotMemoryCache.invalidate(file)
            file.delete()
            backup.delete()
        }
    }

    private fun loaded(store: AtomicVectorSnapshotStore): VectorSnapshot =
        (store.load("model", 2) as VectorSnapshotLoadResult.Loaded).snapshot

    private fun snapshot(id: String) =
        VectorSnapshot(
            "model",
            2,
            listOf(StoredVector(id, "0".repeat(64), 1L, listOf(1f, 0f))),
        )
}
