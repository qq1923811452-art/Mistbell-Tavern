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
        val settings = BackupEntry("data/settings.json", 0, hash)
        val memoryNames = listOf(
            "mistbell-v3-memory-texts.bin",
            "mistbell-v3-memory-facts.bin",
            "mistbell-v3-memory-jobs.bin",
            "mistbell-v3-temporal-facts.bin",
            "mistbell-v3-vectors.bin",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                BackupManifest(
                    1, "now", "test", 2, BackupValidator.MAX_ENTRY_BYTES + 1,
                    listOf(
                        settings,
                        BackupEntry("data/memory/mistbell-v3-vectors.bin", BackupValidator.MAX_ENTRY_BYTES + 1, hash),
                    ),
                ),
            )
        }

        val oversizedTotalEntries = listOf(settings) + memoryNames.map { name ->
            BackupEntry("data/memory/$name", BackupValidator.MAX_ENTRY_BYTES, hash)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                BackupManifest(
                    1, "now", "test", oversizedTotalEntries.size,
                    oversizedTotalEntries.sumOf(BackupEntry::size),
                    oversizedTotalEntries,
                ),
            )
        }

        val tooManyEntries = listOf(settings) + (0 until BackupValidator.MAX_ENTRIES).map { index ->
            BackupEntry("data/worldbooks/$index.json", 0, hash)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                BackupManifest(1, "now", "test", tooManyEntries.size, 0, tooManyEntries),
            )
        }
    }
}
