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
        val full = (0 until PersistedChatMessage.MAX_VARIANTS).map { "v$it" }
        assertEquals(full, MessageVariants.append(full, "overflow"))
    }

    @Test
    fun selectClampsOutOfRangeIndexes() {
        assertEquals(0, MessageVariants.select(listOf("a", "b"), -1))
        assertEquals(1, MessageVariants.select(listOf("a", "b"), 9))
        assertEquals(0, MessageVariants.select(emptyList(), 3))
    }

    @Test
    fun editingUpdatesTheActiveVariantWithoutDiscardingAlternatives() {
        val original =
            ChatLine(
                id = "assistant-1",
                role = "assistant",
                content = "first",
                createdAtEpochMillis = 1L,
                deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
                variants = listOf("first", "second"),
                activeVariant = 1,
            )

        val edited = original.withEditedContent("edited")

        assertEquals("edited", edited.content)
        assertEquals(listOf("first", "edited"), edited.variants)
        assertEquals("edited", edited.activeContent)
        assertEquals("first", edited.copy(activeVariant = 0).activeContent)
    }

    @Test
    fun editingMessageWithoutVariantsKeepsCompatibilityFallback() {
        val original =
            ChatLine(
                id = "assistant-2",
                role = "assistant",
                content = "old",
                createdAtEpochMillis = 1L,
                deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
            )

        val edited = original.withEditedContent("new")

        assertEquals("new", edited.content)
        assertEquals(emptyList<String>(), edited.variants)
        assertEquals("new", edited.activeContent)
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
