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
    fun keywordScanIsBoundedForVeryLongMemoryText() {
        val long = MemoryTextRecord("long-keyword", "x".repeat(VectorMemoryRetriever.MAX_KEYWORD_SCAN_CHARACTERS) + " Beijing")
        val queryOnlyAtEnd = VectorMemoryRetriever.retrieve(
            snapshot = VectorSnapshot("embedding-test", 2, emptyList()),
            queryVector = listOf(1f, 0f),
            texts = listOf(long),
            queryText = "Beijing",
            minimumScore = -1.0,
        )
        assertTrue(queryOnlyAtEnd.isEmpty())
    }

    @Test
    fun goldenSetKeepsRelevantMemoriesInTopOneAcrossSemanticAndKeywordQueries() {
        data class Case(
            val query: String,
            val queryVector: List<Float>,
            val relevant: MemoryTextRecord,
            val distractor: MemoryTextRecord,
            val relevantVector: List<Float>,
            val distractorVector: List<Float>,
        )
        val cases = listOf(
            Case(
                query = "北京旅行",
                queryVector = listOf(1f, 0f),
                relevant = MemoryTextRecord("golden-beijing", "去年去北京旅行，参观了故宫。"),
                distractor = MemoryTextRecord("golden-weather", "今天的天气很好，适合散步。"),
                relevantVector = listOf(0.8f, 0.6f),
                distractorVector = listOf(1f, 0f),
            ),
            Case(
                query = "Qualcomm HTP",
                queryVector = listOf(0f, 1f),
                relevant = MemoryTextRecord("golden-htp", "Qualcomm HTP V75 inference diagnostics"),
                distractor = MemoryTextRecord("golden-gpu", "GPU rendering performance and frame rate"),
                relevantVector = listOf(0.6f, 0.8f),
                distractorVector = listOf(0f, 1f),
            ),
            Case(
                query = "concise answers",
                queryVector = listOf(1f, 0f),
                relevant = MemoryTextRecord("golden-style", "User prefers concise answers."),
                distractor = MemoryTextRecord("golden-essay", "Long detailed essay writing style."),
                relevantVector = listOf(0.7f, 0.7f),
                distractorVector = listOf(1f, 0f),
            ),
        )
        val topOneHits = cases.count { case ->
            val snapshot = VectorSnapshot(
                modelId = "golden-test",
                dimensions = 2,
                records = listOf(
                    StoredVector("v-${case.relevant.id}", case.relevant.sourceTextSha256, 1L, case.relevantVector),
                    StoredVector("v-${case.distractor.id}", case.distractor.sourceTextSha256, 2L, case.distractorVector),
                ),
            )
            VectorMemoryRetriever.retrieve(
                snapshot = snapshot,
                queryVector = case.queryVector,
                texts = listOf(case.distractor, case.relevant),
                queryText = case.query,
                limit = 2,
            ).firstOrNull()?.id == case.relevant.id
        }
        assertEquals(3, topOneHits)
    }

    @Test
    fun defaultCosineThresholdRejectsWeakSemanticCandidates() {
        val weak = MemoryTextRecord("weak", "Unrelated memory")
        val strong = MemoryTextRecord("strong", "Relevant memory")
        val ranked = VectorSnapshot(
            modelId = "embedding-test",
            dimensions = 2,
            records = listOf(
                StoredVector("weak-vector", weak.sourceTextSha256, 1L, listOf(0.2f, 0.98f)),
                StoredVector("strong-vector", strong.sourceTextSha256, 2L, listOf(1f, 0f)),
            ),
        )
        val hits = VectorSimilaritySearch.search(ranked, listOf(1f, 0f))
        assertEquals(listOf("strong-vector"), hits.map(VectorSearchHit::id))
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
    /**
     * Small deterministic golden set: each query has one expected memory and orthogonal
     * distractors. This protects threshold/RRF behavior from silent relevance regressions.
     */
    @Test
    fun goldenSetKeepsExpectedMemoryAtTopForEnglishAndChineseQueries() {
        val goldenMemories = listOf(
            MemoryTextRecord("concise", "The user prefers concise answers."),
            MemoryTextRecord("qualcomm", "Qualcomm HTP runtime failure diagnostics."),
            MemoryTextRecord("beijing", "用户今天去了北京并计划旅行。"),
        )
        val goldenSnapshot = VectorSnapshot(
            modelId = "golden-test-model",
            dimensions = 3,
            records = listOf(
                StoredVector("concise-vector", goldenMemories[0].sourceTextSha256, 1L, listOf(1f, 0f, 0f)),
                StoredVector("qualcomm-vector", goldenMemories[1].sourceTextSha256, 2L, listOf(0f, 1f, 0f)),
                StoredVector("beijing-vector", goldenMemories[2].sourceTextSha256, 3L, listOf(0f, 0f, 1f)),
            ),
        )
        data class GoldenCase(val query: String, val vector: List<Float>, val expectedId: String)
        val cases = listOf(
            GoldenCase("concise answers", listOf(1f, 0f, 0f), "concise"),
            GoldenCase("Qualcomm HTP runtime diagnostics", listOf(0f, 1f, 0f), "qualcomm"),
            GoldenCase("北京旅行", listOf(0f, 0f, 1f), "beijing"),
        )

        cases.forEach { golden ->
            val results = VectorMemoryRetriever.retrieve(
                snapshot = goldenSnapshot,
                queryVector = golden.vector,
                texts = goldenMemories,
                queryText = golden.query,
                limit = 3,
                minimumScore = 0.35,
            )
            assertTrue("Golden query should return at least one result: ${golden.query}", results.isNotEmpty())
            assertEquals("Unexpected top memory for query: ${golden.query}", golden.expectedId, results.first().id)
            assertTrue("Top result should meet the configured threshold", results.first().similarity >= 0.35)
        }
    }

}
