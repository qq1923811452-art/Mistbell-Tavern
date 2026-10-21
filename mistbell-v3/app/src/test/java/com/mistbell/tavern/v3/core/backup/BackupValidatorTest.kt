package com.mistbell.tavern.v3.core.backup

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupValidatorTest {
    @Test
    fun rejectsTraversalAbsoluteAndOverlongPaths() {
        listOf(
            "data/../../evil",
            "../evil",
            "/data/worldbooks/a.json",
            "data/" + "a".repeat(1025),
            "data/" + "a".repeat(256) + "/x",
            "data\\worldbooks\\evil.json",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { BackupValidator.validatePath(path) }
        }
    }

    @Test
    fun rejectsManifestWithoutRequiredSettingsPayload() {
        val manifest = BackupManifest(
            1, "now", "test", 1, 0,
            listOf(BackupEntry("data/memory/mistbell-v3-vectors.bin", 0, "0".repeat(64))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(manifest)
        }
    }

    @Test
    fun rejectsMemoryPathsThatCouldEscapeTheSupportedMemoryStore() {
        val hash = "0".repeat(64)
        val entries = listOf(
            BackupEntry("data/settings.json", 0, hash),
            BackupEntry("data/memory/worldbooks/role.json", 0, hash),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(BackupManifest(1, "now", "test", 2, 0, entries))
        }
    }

    @Test
    fun rejectsPerEntryTotalAndEntryCountLimits() {
        val hash = "0".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                BackupManifest(1, "now", "test", 1, BackupValidator.MAX_ENTRY_BYTES + 1,
                    listOf(BackupEntry("data/huge.bin", BackupValidator.MAX_ENTRY_BYTES + 1, hash))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                BackupManifest(1, "now", "test", 1, BackupValidator.MAX_TOTAL_UNCOMPRESSED_BYTES + 1,
                    listOf(BackupEntry("data/huge.bin", BackupValidator.MAX_TOTAL_UNCOMPRESSED_BYTES + 1, hash))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                BackupManifest(1, "now", "test", BackupValidator.MAX_ENTRIES + 1, 0, emptyList()),
            )
        }
    }
}
