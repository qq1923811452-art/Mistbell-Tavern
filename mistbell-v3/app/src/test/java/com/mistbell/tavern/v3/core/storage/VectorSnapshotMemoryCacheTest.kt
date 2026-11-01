package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class VectorSnapshotMemoryCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun unchangedIdentityReusesDecodedSnapshot() {
        val file = temporary.newFile().apply { writeText("1234") }
        val key = requireNotNull(VectorSnapshotMemoryCache.key(file, "model", 2))
        val snapshot = VectorSnapshot("model", 2, emptyList())
        VectorSnapshotMemoryCache.store(file, key, snapshot)
        assertSame(snapshot, VectorSnapshotMemoryCache.find(file, key))
        VectorSnapshotMemoryCache.invalidate(file)
        assertNull(VectorSnapshotMemoryCache.find(file, key))
    }

    @Test
    fun replacementWithSameLengthAndTimestampCannotHitOldEntry() {
        val file = temporary.newFile().apply { writeText("1234") }
        val old = requireNotNull(VectorSnapshotMemoryCache.key(file, "model", 2))
        VectorSnapshotMemoryCache.store(file, old, VectorSnapshot("model", 2, emptyList()))
        val replacement = temporary.newFile().apply { writeText("5678") }
        Files.setLastModifiedTime(replacement.toPath(), old.identity.lastModified)
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val latest = requireNotNull(VectorSnapshotMemoryCache.key(file, "model", 2))
        assertEquals(old.identity.length, latest.identity.length)
        assertEquals(old.identity.lastModified, latest.identity.lastModified)
        assertNotEquals(old.identity.fileKey, latest.identity.fileKey)
        assertNull(VectorSnapshotMemoryCache.find(file, old))
    }

    @Test
    fun changedFileCannotPublishAnOldMappingUnderItsOldKey() {
        val file = temporary.newFile().apply { writeText("1234") }
        val old = requireNotNull(VectorSnapshotMemoryCache.key(file, "model", 2))
        file.writeText("different content")
        VectorSnapshotMemoryCache.store(file, old, VectorSnapshot("model", 2, emptyList()))
        assertNull(VectorSnapshotMemoryCache.find(file, old))
    }

    @Test
    fun storesUsingEquivalentPathsShareALock() {
        val file = temporary.newFile()
        val alias = java.io.File(file.parentFile, "./${file.name}")
        assertSame(VectorSnapshotMemoryCache.lockFor(file), VectorSnapshotMemoryCache.lockFor(alias))
    }
}
