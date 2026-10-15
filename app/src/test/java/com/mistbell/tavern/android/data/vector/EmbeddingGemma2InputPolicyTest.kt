package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingGemma2InputPolicyTest {
    @Test
    fun queryUsesSearchQueryInstructionAndTrimsWhitespace() {
        assertEquals(
            "task: search result | query: Who did Mira meet?",
            EmbeddingGemma2InputPolicy.format("  Who did Mira meet?  ", isQuery = true),
        )
    }

    @Test
    fun indexedMemoryUsesSearchResultInstructionAndTrimsWhitespace() {
        assertEquals(
            "title: none | text: Mira met Rowan at the station.",
            EmbeddingGemma2InputPolicy.format(" Mira met Rowan at the station. ", isQuery = false),
        )
    }
}
