package com.mistbell.tavern.v3.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupManifestTest {
    private val hash = "a".repeat(64)

    @Test
    fun manifestRoundTripsAndPreservesEntryMetadata() {
        val manifest = BackupManifest(
            version = 1,
            createdAt = "2026-10-21T12:00:00Z",
            appVersion = "3.0.0-dev",
            entryCount = 1,
            totalBytes = 3,
            entries = listOf(BackupEntry("data/settings.json", 3, hash)),
        )
        val parsed = BackupManifest.parse(manifest.toJson())
        assertEquals(manifest, parsed)
        BackupValidator.validateManifest(parsed)
    }

    @Test
    fun rejectsUnsupportedVersionAndCountMismatch() {
        val valid = BackupManifest(
            1, "2026-10-21T12:00:00Z", "test", 1, 3,
            listOf(BackupEntry("data/settings.json", 3, hash)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(valid.copy(version = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(valid.copy(entryCount = 2))
        }
    }
}
