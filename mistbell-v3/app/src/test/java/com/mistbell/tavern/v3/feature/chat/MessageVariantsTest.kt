package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageVariantsTest {

    @Test
    fun appendsTrimmedAlternativesAndIgnoresBlankOnes() {
        assertEquals(listOf("a", "b"), MessageVariants.append(listOf("a"), "  b  "))
        assertEquals(listOf("a"), MessageVariants.append(listOf("a"), "   "))
        assertEquals(emptyList<String>(), MessageVariants.append(emptyList(), ""))
    }

    @Test
    fun refusesMoreThanTheStorageLimit() {
        val full = (0 until ChatHistoryStore.PersistedChatMessage.MAX_VARIANTS).map { "v$it" }
        assertEquals(full, MessageVariants.append(full, "overflow"))
    }

    @Test
    fun selectClampsOutOfRangeIndexes() {
        assertEquals(0, MessageVariants.select(listOf("a", "b"), -1))
        assertEquals(1, MessageVariants.select(listOf("a", "b"), 9))
        assertEquals(0, MessageVariants.select(emptyList(), 3))
    }

    @Test
    fun nextAndPreviousWrapAround() {
        val variants = listOf("a", "b", "c")
        assertEquals(1, MessageVariants.next(variants, 0))
        assertEquals(0, MessageVariants.next(variants, 2))
        assertEquals(2, MessageVariants.previous(variants, 0))
        assertEquals(0, MessageVariants.previous(variants, 1))
    }
}
