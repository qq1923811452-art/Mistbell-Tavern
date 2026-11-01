package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.MemoryOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryOverrideCodecTest {
    @Test
    fun originalKeyAndRevisionTombstoneSurviveRoundTrip() {
        val entry = MemoryOverride("scope", "fact", "new subject", "relation", "value", 7L, 5L, "original", true)
        assertEquals(listOf(entry), MemoryOverrideCodec.decode(MemoryOverrideCodec.encode(listOf(entry))))
    }

    @Test
    fun versionOneWithoutNewFieldsStillLoads() {
        val payload =
            """
            {"version":1,"overrides":[{"scopeId":"scope","factId":"fact","subject":"s",
            "predicate":"p","value":"v","revision":1,"updatedAt":1}]}
            """.trimIndent()
        val entry = MemoryOverrideCodec.decode(payload.toByteArray()).single()
        assertNull(entry.sourceLogicalKey)
        assertFalse(entry.reverted)
    }

    @Test
    fun oversizedLegalRecordsAreRejectedBeforeWriting() {
        val field = "x".repeat(MemoryOverride.MAX_FIELD_CHARS)
        val records = List(1_500) { index -> MemoryOverride("scope", "fact-$index", field, field, field, 1L, 1L) }
        val failure = runCatching { MemoryOverrideCodec.encode(records) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedReadIsRejectedBeforeJsonParsing() {
        MemoryOverrideCodec.decode(ByteArray(AtomicMemoryOverrideStore.MAX_STORE_BYTES.toInt() + 1))
    }
}
