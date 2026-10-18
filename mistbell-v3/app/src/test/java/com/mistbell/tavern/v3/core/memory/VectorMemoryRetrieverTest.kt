package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMemoryRetrieverTest {
    private val firstText = MemoryTextRecord("memory-a", "User prefers concise answers.")
    private val secondText = MemoryTextRecord("memory-b", "User is building a local vector memory system.")
    private val staleText = MemoryTextRecord("memory-stale", "This text no longer matches its embedding.")

    private val snapshot = VectorSnapshot(
        modelId = "embedding-test",
        dimensions = 2,
        records = listOf(
            StoredVector("vector-a", firstText.sourceTextSha256, 10L, listOf(1f, 0f)),
            StoredVector("vector-b", secondText.sourceTextSha256, 20L, listOf(0.8f, 0.2f)),
            StoredVector("vector-stale", "b".repeat(64), 30L, listOf(0.7f, 0.3f)),
        ),
    )

    @Test
    fun retrievesMatchingSourceTextInSimilarityOrder() {
        val result = VectorMemoryRetriever.retrieve(
            snapshot = snapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(secondText, firstText, staleText),
            minimumScore = 0.5,
        )

        assertEquals(listOf(firstText.id, secondText.id), result.map(RetrievedMemory::id))
        assertTrue(result[0].similarity >= result[1].similarity)
    }

    @Test
    fun skipsStaleVectorsWithoutMatchingTextHash() {
        val result = VectorMemoryRetriever.retrieve(
            snapshot = snapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(firstText),
            minimumScore = 0.5,
        )

        assertEquals(listOf(firstText.id), result.map(RetrievedMemory::id))
    }

    @Test
    fun enforcesCharacterBudgetAndDeduplicatesIdenticalText() {
        val duplicate = MemoryTextRecord("memory-a-copy", firstText.text)
        val duplicateVector = StoredVector(
            id = "vector-a-copy",
            sourceTextSha256 = duplicate.sourceTextSha256,
            createdAtEpochMillis = 11L,
            values = listOf(0.99f, 0.01f),
        )
        val withDuplicate = snapshot.copy(records = snapshot.records + duplicateVector)

        val result = VectorMemoryRetriever.retrieve(
            snapshot = withDuplicate,
            queryVector = listOf(1f, 0f),
            texts = listOf(firstText, duplicate, secondText),
            minimumScore = 0.5,
            maxCharacters = 40,
        )

        assertEquals(1, result.size)
        assertEquals(firstText.text.length, result.sumOf { it.text.length })
        assertEquals(firstText.id, result.single().id)
    }

    @Test
    fun skipsOverBudgetMemoryAndContinuesToShorterRelevantMemory() {
        val longText = MemoryTextRecord("long", "highly relevant")
        val shortText = MemoryTextRecord("short", "ok")
        val rankedSnapshot = VectorSnapshot(
            modelId = "embedding-test",
            dimensions = 2,
            records = listOf(
                StoredVector("long-vector", longText.sourceTextSha256, 1L, listOf(1f, 0f)),
                StoredVector("short-vector", shortText.sourceTextSha256, 2L, listOf(0.8f, 0.2f)),
            ),
        )

        val result = VectorMemoryRetriever.retrieve(
            snapshot = rankedSnapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(longText, shortText),
            maxCharacters = 5,
        )

        assertEquals(listOf(shortText.id), result.map(RetrievedMemory::id))
    }


    @Test
    fun fusesVectorAndKeywordRanksUsingReciprocalRankFusion() {
        val keywordStrong = MemoryTextRecord("keyword-strong", "Qualcomm HTP runtime failure diagnostics")
        val vectorStrong = MemoryTextRecord("vector-strong", "The device has a hardware accelerator")
        val mixed = MemoryTextRecord("mixed", "Qualcomm accelerator runtime diagnostics")
        val rankedSnapshot = VectorSnapshot(
            modelId = "embedding-test",
            dimensions = 2,
            records = listOf(
                StoredVector("vector-strong", vectorStrong.sourceTextSha256, 1L, listOf(1f, 0f)),
                StoredVector("mixed", mixed.sourceTextSha256, 2L, listOf(0.8f, 0.6f)),
                StoredVector("keyword-strong", keywordStrong.sourceTextSha256, 3L, listOf(0f, 1f)),
            ),
        )

        val result = VectorMemoryRetriever.retrieve(
            snapshot = rankedSnapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(keywordStrong, vectorStrong, mixed),
            queryText = "Qualcomm HTP runtime diagnostics",
            limit = 3,
            minimumScore = -1.0,
        )

        assertEquals(3, result.size)
        assertTrue(result.any { it.id == keywordStrong.id })
        assertTrue(result.any { it.id == vectorStrong.id })
        assertTrue(result.any { it.id == mixed.id })
    }

    @Test
    fun keywordSearchCanRecoverAnExactTermMatchBeyondVectorTopRank() {
        val exact = MemoryTextRecord("exact", "Vector retrieval with reciprocal rank fusion")
        val semantic = MemoryTextRecord("semantic", "A general memory about relevance")
        val rankedSnapshot = VectorSnapshot(
            modelId = "embedding-test",
            dimensions = 2,
            records = listOf(
                StoredVector("semantic", semantic.sourceTextSha256, 1L, listOf(1f, 0f)),
                StoredVector("exact", exact.sourceTextSha256, 2L, listOf(0f, 1f)),
            ),
        )

        val result = VectorMemoryRetriever.retrieve(
            snapshot = rankedSnapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(semantic, exact),
            queryText = "reciprocal rank fusion",
            limit = 2,
            minimumScore = -1.0,
        )

        assertEquals("exact", result.first().id)
    }
    @Test
    fun rejectsDuplicateTextIdsAndInvalidBudgets() {
        assertTrue(runCatching {
            VectorMemoryRetriever.retrieve(
                snapshot,
                listOf(1f, 0f),
                listOf(firstText, firstText),
            )
        }.isFailure)
        assertTrue(runCatching {
            VectorMemoryRetriever.retrieve(
                snapshot,
                listOf(1f, 0f),
                listOf(firstText),
                maxCharacters = 0,
            )
        }.isFailure)
    }
}
