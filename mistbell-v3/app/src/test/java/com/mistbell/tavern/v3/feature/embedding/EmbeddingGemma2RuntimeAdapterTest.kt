package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingGemma2RuntimeAdapterTest {
    private val rawStatus =
        InferenceRuntimeStatus(
            phase = InferencePhase.READY,
            modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
            backend = InferenceBackend.CPU,
            runtimeId = "fake-litert-engine",
            backendEvidence = BackendEvidence.RUNTIME_REPORTED,
        )

    private fun engine(inference: suspend (List<String>) -> EmbeddingBatch) =
        object : EmbeddingGemma2InferenceEngine {
            override val runtimeId = "fake-litert-engine"

            override suspend fun infer(formattedInputs: List<String>) = inference(formattedInputs)
        }

    @Test
    fun queryAndDocumentUseDifferentFormatsAndNormalizedOutput(): Unit =
        runBlocking {
            val seen = mutableListOf<List<String>>()
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            seen += inputs
                            EmbeddingBatch(
                                vectors = inputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f } },
                                status = rawStatus,
                            )
                        },
                    outputDimensions = 128,
                )

            val queryResult = adapter.embedQueries(listOf("Who left the tavern?"))
            val documentResult = adapter.embedDocuments(listOf("Mira left at midnight."), listOf("Night 1"))

            assertEquals("task: search result | query: Who left the tavern?", seen[0][0])
            assertEquals("title: Night 1 | text: Mira left at midnight.", seen[1][0])
            assertEquals(128, queryResult.vectors.single().size)
            assertEquals(128, documentResult.vectors.single().size)
            assertEquals(InferenceBackend.CPU, queryResult.status.backend)
            assertEquals(adapter.runtimeId, queryResult.status.runtimeId)
        }

    @Test
    fun rejectsHigherDimensionsBeforeRunningEmbeddingGemma2() {
        var calls = 0
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine {
                            calls++
                            error("The engine must not run for unsupported dimensions.")
                        },
                    outputDimensions = 1024,
                )
            }
        assertTrue(error.message!!.contains("Choose 128, 256, 512, or 768."))
        assertEquals(0, calls)
    }

    @Test
    fun allowsValidVectorsWhenHardwareBackendIsUnknown(): Unit =
        runBlocking {
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine {
                            EmbeddingBatch(
                                vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                                status =
                                    rawStatus.copy(
                                        backend = InferenceBackend.UNKNOWN,
                                        backendEvidence = BackendEvidence.CONFIGURED_ONLY,
                                    ),
                            )
                        },
                )

            val result = adapter.embedQueries(listOf("query"))

            assertEquals(1, result.vectors.size)
            assertEquals(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS, result.vectors.single().size)
            assertEquals(InferenceBackend.UNKNOWN, result.status.backend)
            assertEquals(BackendEvidence.CONFIGURED_ONLY, result.status.backendEvidence)
        }

    @Test
    fun rejectsUnavailableBackendEvidenceAndWrongModel(): Unit =
        runBlocking {
            val unavailableEvidence =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine {
                            EmbeddingBatch(
                                vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                                status = rawStatus.copy(backendEvidence = BackendEvidence.NOT_AVAILABLE),
                            )
                        },
                )
            assertThrows(EmbeddingOutputException::class.java) {
                runBlocking { unavailableEvidence.embedQueries(listOf("query")) }
            }

            val wrongModel =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine {
                            EmbeddingBatch(
                                vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                                status = rawStatus.copy(modelId = "another-model"),
                            )
                        },
                )
            assertThrows(EmbeddingOutputException::class.java) {
                runBlocking { wrongModel.embedDocuments(listOf("document")) }
            }
        }

    @Test
    fun safelyTruncatesOversizedFormattedTitleBeforeCallingEngine(): Unit =
        runBlocking {
            var calls = 0
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            calls++
                            EmbeddingBatch(
                                inputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f } },
                                rawStatus,
                            )
                        },
                )
            adapter.embedDocuments(
                listOf("short text"),
                listOf("中".repeat(EmbeddingGemma2RuntimeAdapter.MAX_FORMATTED_INPUT_BYTES)),
            )
            assertEquals(1, calls)
        }

    @Test
    fun batchEmbeds32TextsInFourEngineCallsAndPreservesOrder(): Unit =
        runBlocking {
            EmbeddingVectorCache.clear()
            var calls = 0
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            calls++
                            EmbeddingBatch(
                                vectors =
                                    inputs.map { input ->
                                        val index = Regex("item-(\\d+)").find(input)!!.groupValues[1].toInt() + 1
                                        List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { dimension ->
                                            when (dimension) {
                                                0 -> index.toFloat()
                                                1 -> 1f
                                                else -> 0f
                                            }
                                        }
                                    },
                                status = rawStatus,
                            )
                        },
                )
            val inputs = (0 until 32).map { "item-${it.toString().padStart(2, '0')}" }
            val result = adapter.embedBatch(inputs)
            assertEquals(32, result.size)
            assertEquals(4, calls)
            assertTrue(result.zipWithNext().all { (left, right) -> left[0] < right[0] })
        }

    @Test
    fun emptyBatchDoesNotCallEngine(): Unit =
        runBlocking {
            var calls = 0
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine {
                            calls++
                            error("Must not infer.")
                        },
                )
            assertTrue(adapter.embedBatch(emptyList()).isEmpty())
            assertEquals(0, calls)
        }

    @Test
    fun repeatedInputUsesCacheWithoutCallingEngineAgain(): Unit =
        runBlocking {
            EmbeddingVectorCache.clear()
            var calls = 0
            val events = mutableListOf<String>()
            val loggedAdapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            calls++
                            EmbeddingBatch(
                                vectors = inputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 0.5f } },
                                status = rawStatus,
                            )
                        },
                    onDiagnostic = { event, _ -> events += event },
                )
            val first = loggedAdapter.embedQueries(listOf("same cache input"))
            val second = loggedAdapter.embedQueries(listOf("same cache input"))
            assertEquals(1, calls)
            assertEquals(first.vectors, second.vectors)
            assertTrue("embedding.cache.miss" in events)
            assertTrue("embedding.cache.hit" in events)
        }

    @Test
    fun cacheEntryFromDifferentRuntimeIsReusedWithoutRequiringSameEngineInstance(): Unit =
        runBlocking {
            EmbeddingVectorCache.clear()
            var firstCalls = 0
            var secondCalls = 0
            val firstAdapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            firstCalls++
                            EmbeddingBatch(
                                vectors = inputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 0.5f } },
                                status = rawStatus,
                            )
                        },
                )
            firstAdapter.embedQueries(listOf("runtime-sensitive cache input"))
            val secondEngine =
                object : EmbeddingGemma2InferenceEngine {
                    override val runtimeId = "another-runtime"

                    override suspend fun infer(formattedInputs: List<String>): EmbeddingBatch {
                        secondCalls++
                        return EmbeddingBatch(
                            vectors = formattedInputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 0.25f } },
                            status = rawStatus.copy(runtimeId = runtimeId),
                        )
                    }
                }
            val secondAdapter = EmbeddingGemma2RuntimeAdapter(engine = secondEngine)
            val result = secondAdapter.embedQueries(listOf("runtime-sensitive cache input"))
            assertEquals(1, firstCalls)
            assertEquals(0, secondCalls)
            assertEquals("fake-litert-engine", result.status.runtimeId)
        }

    @Test
    fun cachedQueryReturnsWithoutConstructingOrInitializingAnEngine(): Unit =
        runBlocking {
            EmbeddingVectorCache.clear()
            var calls = 0
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            calls++
                            EmbeddingBatch(inputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 0.75f } }, rawStatus)
                        },
                )
            val query = "cache survives engine close"
            val first = adapter.embedQueries(listOf(query))
            val cached = EmbeddingGemma2RuntimeAdapter.cachedQuery(query, modelProfileId = EmbeddingGemma2TextPolicy.MODEL_ID)
            assertEquals(1, calls)
            assertTrue(cached != null)
            assertEquals(first.vectors, cached!!.vectors)
            assertEquals(null, cached.status.lastExecutionMillis)
            assertTrue(cached.status.isCachedResult)
        }

    @Test
    fun truncatesLongInputAndLogsOriginalAndTruncatedLengths(): Unit =
        runBlocking {
            EmbeddingVectorCache.clear()
            val events = mutableListOf<Pair<String, String>>()
            var sentInput: String? = null
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            sentInput = inputs.single()
                            EmbeddingBatch(
                                vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                                status = rawStatus,
                            )
                        },
                    onDiagnostic = { event, detail -> events += event to detail },
                )

            adapter.embedQueries(listOf("x".repeat(3_000)))

            assertTrue(
                sentInput!!.toByteArray(Charsets.UTF_8).size <=
                    EmbeddingGemma2RuntimeAdapter.MAX_FORMATTED_INPUT_BYTES,
            )
            assertTrue(sentInput!!.startsWith("task: search result | query: "))
            assertTrue(
                events.any {
                    it.first == "embedding.input.truncated" &&
                        it.second.contains("originalLength=3000") && it.second.contains("truncatedLength=")
                },
            )
        }

    @Test
    fun logsTruncationForChineseDocumentCutByByteBudget(): Unit =
        runBlocking {
            EmbeddingVectorCache.clear()
            val events = mutableListOf<Pair<String, String>>()
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            EmbeddingBatch(
                                vectors = inputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f } },
                                status = rawStatus,
                            )
                        },
                    onDiagnostic = { event, detail -> events += event to detail },
                )

            adapter.embedDocuments(listOf("中".repeat(900)))

            assertTrue(
                events.any {
                    it.first == "embedding.input.truncated" && it.second.contains("originalLength=900")
                },
            )
        }

    @Test
    fun cacheKeySeparatesModelAndDimensions() {
        val a = EmbeddingVectorCache.key("same", "model-a", 768)
        val b = EmbeddingVectorCache.key("same", "model-b", 768)
        val c = EmbeddingVectorCache.key("same", "model-a", 256)
        val d = EmbeddingVectorCache.key("same", "model-a", 768, policyVersion = EmbeddingGemma2TextPolicy.POLICY_VERSION + 1)
        val e = EmbeddingVectorCache.key("same", "model-a", 768, modelProfileId = "other-profile")
        assertTrue(a != b)
        assertTrue(a != c)
        assertTrue(a != d)
        assertTrue(a != e)
    }

    @Test
    fun rejectsInvalidConfiguredBatchSizes() {
        assertTrue(
            runCatching {
                EmbeddingGemma2RuntimeAdapter(engine = engine { error("Must not infer") }, configuredBatchSize = 0)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                EmbeddingGemma2RuntimeAdapter(
                    engine = engine { error("Must not infer") },
                    configuredBatchSize = EmbeddingGemma2RuntimeAdapter.MAX_CONFIGURED_BATCH_SIZE + 1,
                )
            }.isFailure,
        )
    }

    @Test
    fun longChineseTitleIsByteBoundedWithoutEngineFailure(): Unit =
        runBlocking {
            var input: String? = null
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine =
                        engine { inputs ->
                            input = inputs.single()
                            EmbeddingBatch(
                                listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                                rawStatus,
                            )
                        },
                )
            adapter.embedDocuments(listOf("中".repeat(2_000)), listOf("标题".repeat(1_000)))
            assertTrue(
                input!!.toByteArray(Charsets.UTF_8).size <=
                    EmbeddingGemma2RuntimeAdapter.MAX_FORMATTED_INPUT_BYTES,
            )
            assertTrue(input!!.startsWith("title: "))
        }

    @Test
    fun rejectsTitleCountMismatch(): Unit =
        runBlocking {
            val adapter =
                EmbeddingGemma2RuntimeAdapter(
                    engine = engine { error("Engine must not run when title count is invalid.") },
                )
            assertThrows(EmbeddingOutputException::class.java) {
                runBlocking { adapter.embedDocuments(listOf("a", "b"), listOf("only one")) }
            }
        }
}
