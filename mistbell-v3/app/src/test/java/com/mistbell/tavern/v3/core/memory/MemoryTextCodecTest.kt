package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryTextCodecTest {
    @Test
    fun roundTripsTextAndKeepsDeterministicEncoding() {
        val records = listOf(
            MemoryTextRecord("memory-b", "A fact with Unicode: 世界"),
            MemoryTextRecord("memory-a", "A stable preference."),
        )

        val encoded = MemoryTextCodec.encode(records)

        assertEquals(records.sortedBy(MemoryTextRecord::id), MemoryTextCodec.decode(encoded))
        assertEquals(encoded.toList(), MemoryTextCodec.encode(records.reversed()).toList())
    }

    @Test
    fun rejectsChecksumMismatch() {
        val encoded = MemoryTextCodec.encode(
            listOf(MemoryTextRecord("memory-1", "A remembered fact.")),
        )
        encoded[encoded.lastIndex - 1] = (encoded[encoded.lastIndex - 1].toInt() xor 0x01).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            MemoryTextCodec.decode(encoded)
        }
    }

    @Test
    fun rejectsDuplicateIds() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryTextCodec.encode(
                listOf(
                    MemoryTextRecord("duplicate", "First fact."),
                    MemoryTextRecord("duplicate", "Second fact."),
                ),
            )
        }
    }

    @Test
    fun rejectsTamperedTextEvenWhenEnvelopeChecksumIsRecomputedNotProvided() {
        val encoded = MemoryTextCodec.encode(
            listOf(MemoryTextRecord("memory-1", "A remembered fact.")),
        )
        encoded[encoded.size / 2] = (encoded[encoded.size / 2].toInt() xor 0x01).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            MemoryTextCodec.decode(encoded)
        }
    }
}
