package com.mistbell.tavern.v3.core.backup

import org.junit.Assert.assertEquals
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

    @Test
    fun rejectsWindowsAbsoluteNulAndAmbiguousPaths() {
        listOf(
            "C:/data/settings.json",
            "C:\\data\\settings.json",
            "\\\\server\\share\\data\\settings.json",
            "\\data\\settings.json",
            "data/settings\u0000.json",
            "data//settings.json",
            "data/./settings.json",
            "data/",
            "data",
            "elsewhere/settings.json",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { BackupValidator.validatePath(path) }
        }
    }

    @Test
    fun acceptsPathsAtTheMaximumLengthAndSegmentBoundary() {
        BackupValidator.validatePath("data/" + "a".repeat(BackupValidator.MAX_SEGMENT_CHARS))
        val maximumLength = "data/" + "a".repeat(255) + "/" + "b".repeat(255) + "/" +
            "c".repeat(255) + "/" + "d".repeat(251)
        assertEquals(BackupValidator.MAX_PATH_CHARS, maximumLength.length)
        BackupValidator.validatePath(maximumLength)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validatePath(maximumLength + "x")
        }
    }

    @Test
    fun acceptsEverySupportedUserDataPath() {
        val entries = listOf(
            BackupEntry("data/settings.json", 1, hash),
            BackupEntry("data/mistbell-v3-chat.db", 2, hash),
            BackupEntry("data/mistbell-v3-sessions.db", 3, hash),
            BackupEntry("data/memory/mistbell-v3-vectors.bin", 4, hash),
            BackupEntry(
                "data/memory/mistbell-v3-memory-texts-0123456789abcdef01234567.bin",
                5,
                hash,
            ),
            BackupEntry("data/worldbooks/role.json", 6, hash),
            BackupEntry("data/worldbooks/nested/role.json", 7, hash),
            BackupEntry("data/characters/hero.json", 8, hash),
            BackupEntry("data/generated-images/generated/hero.png", 9, hash),
        )
        BackupValidator.validateManifest(manifest(entries))
    }

    @Test
    fun rejectsDuplicateManifestPaths() {
        val duplicate = BackupEntry("data/characters/role.json", 0, hash)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                manifest(
                    listOf(
                        BackupEntry("data/settings.json", 0, hash),
                        duplicate,
                        duplicate.copy(),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsManifestTotalsThatDoNotMatchEntrySizes() {
        val settings = BackupEntry("data/settings.json", 3, hash)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(manifest(listOf(settings), totalBytes = 4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(manifest(listOf(settings), totalBytes = 2))
        }
    }

    @Test
    fun rejectsEmptyManifestAndEntryCountMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(manifest(emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                manifest(listOf(BackupEntry("data/settings.json", 0, hash)), entryCount = 2),
            )
        }
    }

    @Test
    fun rejectsUnsupportedManifestFormatAndVersion() {
        val valid = manifest(listOf(BackupEntry("data/settings.json", 0, hash)))
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(valid.copy(format = "other-format"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(valid.copy(version = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(valid.copy(version = BackupManifest.CURRENT_VERSION + 1))
        }
    }

    @Test
    fun rejectsManifestEntriesOutsideTheSupportedDataAllowlist() {
        listOf(
            "data/settings-extra.json",
            "data/worldbooks/notes.txt",
            "data/characters/hero.png",
            "data/generated-images",
            "data/memory/nested/mistbell-v3-vectors.bin",
            "data/memory/unknown.bin",
            "data/other/settings.json",
            "data/mistbell-v3-chat.db.bak",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupValidator.validateManifest(
                    manifest(
                        listOf(
                            BackupEntry("data/settings.json", 0, hash),
                            BackupEntry(path, 0, hash),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun rejectsSummedSizesAboveTotalLimitWhenManifestTotalLooksAllowed() {
        val memoryNames = listOf(
            "mistbell-v3-memory-texts.bin",
            "mistbell-v3-memory-facts.bin",
            "mistbell-v3-memory-jobs.bin",
            "mistbell-v3-temporal-facts.bin",
            "mistbell-v3-vectors.bin",
        )
        val entries = listOf(BackupEntry("data/settings.json", 0, hash)) +
            memoryNames.map { name ->
                BackupEntry("data/memory/$name", BackupValidator.MAX_ENTRY_BYTES, hash)
            }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(
                manifest(entries, totalBytes = BackupValidator.MAX_TOTAL_UNCOMPRESSED_BYTES),
            )
        }
    }

    private fun manifest(
        entries: List<BackupEntry>,
        entryCount: Int = entries.size,
        totalBytes: Long = entries.sumOf(BackupEntry::size),
    ): BackupManifest = BackupManifest(
        version = BackupManifest.CURRENT_VERSION,
        createdAt = "2026-10-22T00:00:00Z",
        appVersion = "3.0.0-dev",
        entryCount = entryCount,
        totalBytes = totalBytes,
        entries = entries,
    )

    private companion object {
        private val hash = "0".repeat(64)
    }
}
