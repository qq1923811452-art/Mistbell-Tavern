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

        assertEquals(2, result.size)
        assertEquals(40, result.sumOf { it.text.length })
        assertEquals(firstText.id, result[0].id)
        assertEquals(secondText.id, result[1].id)
        assertEquals(40 - firstText.text.length, result[1].text.length)
    }

    @Test
    fun truncatesOverBudgetMemoryInsteadOfDroppingTheFirstCandidate() {
        val longText = MemoryTextRecord("long", "这是一条很长的中文记忆")
        val rankedSnapshot = VectorSnapshot(
            modelId = "embedding-test",
            dimensions = 2,
            records = listOf(
                StoredVector("long-vector", longText.sourceTextSha256, 1L, listOf(1f, 0f)),
            ),
        )

        val result = VectorMemoryRetriever.retrieve(
            snapshot = rankedSnapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(longText),
            maxCharacters = 5,
        )

        assertEquals(1, result.size)
        assertEquals("这是一条很", result.single().text)
        assertEquals(5, result.single().text.length)
    }

    @Test
    fun chineseKeywordMatchesInsideAnUnspacedSentence() {
        val chinese = MemoryTextRecord("beijing", "我今天去了北京，之后还去了上海。")
        val semantic = MemoryTextRecord("semantic", "这是一条关于旅行的普通记忆。")
        val rankedSnapshot = VectorSnapshot(
            modelId = "embedding-test",
            dimensions = 2,
            records = listOf(
                StoredVector("semantic", semantic.sourceTextSha256, 1L, listOf(1f, 0f)),
                StoredVector("beijing", chinese.sourceTextSha256, 2L, listOf(0f, 1f)),
            ),
        )

        val result = VectorMemoryRetriever.retrieve(
            snapshot = rankedSnapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(semantic, chinese),
            queryText = "北京",
            limit = 2,
            minimumScore = -1.0,
        )

        assertEquals("beijing", result.first().id)
        assertEquals(MemoryRetrievalSource.HYBRID, result.first().source)
    }

    @Test
    fun chineseSingleCharacterQueryIsNotDiscarded() {
        val sword = MemoryTextRecord("sword", "剑落在雨中")
        val unrelated = MemoryTextRecord("unrelated", "今天的天气很好")
        val rankedSnapshot = VectorSnapshot(
            modelId = "embedding-test",
            dimensions = 2,
            records = listOf(
                StoredVector("unrelated", unrelated.sourceTextSha256, 1L, listOf(1f, 0f)),
                StoredVector("sword", sword.sourceTextSha256, 2L, listOf(0f, 1f)),
            ),
        )

        val result = VectorMemoryRetriever.retrieve(
            snapshot = rankedSnapshot,
            queryVector = listOf(1f, 0f),
            texts = listOf(unrelated, sword),
            queryText = "剑",
            limit = 2,
            minimumScore = -1.0,
        )

        assertEquals("sword", result.first().id)
        assertEquals(MemoryRetrievalSource.HYBRID, result.first().source)
    }

    @Test
    fun mixedEnglishChineseAndLongChineseQueryMatchByKeywords() {
        val exact = MemoryTextRecord("exact", "Gemma 向量检索会在长中文句子中找到北京记忆")
        val semantic = MemoryTextRecord("semantic", "另一条关于硬件加速器的普通记忆")
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
            queryText = "Gemma 向量检索的长句北京",
            limit = 2,
            minimumScore = -1.0,
        )

        assertEquals("exact", result.first().id)
        assertEquals(MemoryRetrievalSource.HYBRID, result.first().source)
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
        assertEquals(MemoryRetrievalSource.HYBRID, result.first { it.id == mixed.id }.source)
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
        assertEquals(MemoryRetrievalSource.HYBRID, result.first().source)
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
