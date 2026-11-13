package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMemoryKeywordScanTest {
    @Test
    fun dropsTheWholePairWhenScanBudgetFallsBetweenItsSurrogates() {
        val prefix = "x".repeat(VectorMemoryRetriever.MAX_KEYWORD_SCAN_CHARACTERS - 1)
        val text = prefix + "\uD83D\uDE00tail"

        assertEquals(prefix, VectorMemoryRetriever.keywordScanPrefix(text))
    }

    @Test
    fun preservesPairThatEndsExactlyAtScanBudget() {
        val prefix = "x".repeat(VectorMemoryRetriever.MAX_KEYWORD_SCAN_CHARACTERS - 2) + "\uD83D\uDE00"

        assertEquals(prefix, VectorMemoryRetriever.keywordScanPrefix(prefix + "tail"))
        assertEquals(prefix, VectorMemoryRetriever.keywordScanPrefix(prefix))
    }

    @Test
    fun keepsShortTextAndUnpairedHighSurrogateWithoutChangingMalformedInputSemantics() {
        assertEquals("", VectorMemoryRetriever.keywordScanPrefix(""))
        assertEquals("北京\uD83D\uDE00", VectorMemoryRetriever.keywordScanPrefix("北京\uD83D\uDE00"))
        val prefix = "x".repeat(VectorMemoryRetriever.MAX_KEYWORD_SCAN_CHARACTERS - 1) + "\uD83D"
        assertEquals(prefix, VectorMemoryRetriever.keywordScanPrefix(prefix + "tail"))
    }

    @Test
    fun keywordMatchingBeforeSplitPairRetainsCaseInsensitiveAndChineseSemantics() {
        val terms = "BeIjInG 北京"
        val prefix = " ".repeat(VectorMemoryRetriever.MAX_KEYWORD_SCAN_CHARACTERS - terms.length - 1) + terms
        val text = prefix + "\uD83D\uDE00 beyond"

        assertEquals(MemoryRetrievalSource.KEYWORD, keywordResults(text, "BEIJING 北京").single().source)
        assertTrue(keywordResults(text, "beyond").isEmpty())
        assertTrue(keywordResults(text, "jing").isEmpty())
    }

    @Test
    fun completeSupplementaryLetterMatchesButOneSplitByBudgetDoesNot() {
        val letter = "\uD801\uDC00"
        val inside = " ".repeat(VectorMemoryRetriever.MAX_KEYWORD_SCAN_CHARACTERS - letter.length) + letter
        val split = " ".repeat(VectorMemoryRetriever.MAX_KEYWORD_SCAN_CHARACTERS - 1) + letter

        assertEquals(MemoryRetrievalSource.KEYWORD, keywordResults(inside + " tail", letter).single().source)
        assertTrue(keywordResults(split + " tail", letter).isEmpty())
    }

    private fun keywordResults(
        text: String,
        query: String,
    ): List<RetrievedMemory> =
        VectorMemoryRetriever.retrieve(
            snapshot = VectorSnapshot("keyword-test", 2, emptyList()),
            queryVector = listOf(1f, 0f),
            texts = listOf(MemoryTextRecord("candidate", text)),
            queryText = query,
        )
}
