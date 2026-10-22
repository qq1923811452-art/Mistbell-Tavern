package com.mistbell.tavern.v3.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupEntryTest {
    private val hash = "0123456789abcdef".repeat(4)

    @Test
    fun acceptsZeroAndMaximumEntrySizes() {
        assertEquals(0L, BackupEntry("data/settings.json", 0, hash).size)
        assertEquals(
            BackupValidator.MAX_ENTRY_BYTES,
            BackupEntry("data/settings.json", BackupValidator.MAX_ENTRY_BYTES, hash).size,
        )
    }

    @Test
    fun rejectsNegativeSize() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupEntry("data/settings.json", -1, hash)
        }
    }

    @Test
    fun rejectsSha256ThatIsNotSixtyFourLowercaseHexCharacters() {
        listOf(
            "",
            "0".repeat(63),
            "0".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
            "0".repeat(63) + "-",
        ).forEach { invalidSha ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupEntry("data/settings.json", 0, invalidSha)
            }
        }
    }
}
