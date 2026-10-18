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
    private val rawStatus = InferenceRuntimeStatus(
        phase = InferencePhase.READY,
        modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
        backend = InferenceBackend.CPU,
        runtimeId = "fake-litert-engine",
        backendEvidence = BackendEvidence.RUNTIME_REPORTED,
    )

    private fun engine(
        inference: suspend (List<String>) -> EmbeddingBatch,
    ) = object : EmbeddingGemma2InferenceEngine {
        override val runtimeId = "fake-litert-engine"
        override suspend fun infer(formattedInputs: List<String>) = inference(formattedInputs)
    }

    @Test
    fun queryAndDocumentUseDifferentFormatsAndNormalizedOutput(): Unit = runBlocking {
        val seen = mutableListOf<List<String>>()
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine { inputs ->
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

        assertEquals("task: search query | text: Who left the tavern?", seen[0][0])
        assertEquals("task: search result | title: Night 1 | text: Mira left at midnight.", seen[1][0])
        assertEquals(128, queryResult.vectors.single().size)
        assertEquals(128, documentResult.vectors.single().size)
        assertEquals(InferenceBackend.CPU, queryResult.status.backend)
        assertEquals(adapter.runtimeId, queryResult.status.runtimeId)
    }

    @Test
    fun rejectsHigherDimensionsBeforeRunningEmbeddingGemma2() {
        var calls = 0
        val error = assertThrows(IllegalArgumentException::class.java) {
            EmbeddingGemma2RuntimeAdapter(
                engine = engine {
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
    fun allowsValidVectorsWhenHardwareBackendIsUnknown(): Unit = runBlocking {
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine {
                EmbeddingBatch(
                    vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                    status = rawStatus.copy(
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
    fun rejectsUnavailableBackendEvidenceAndWrongModel(): Unit = runBlocking {
        val unavailableEvidence = EmbeddingGemma2RuntimeAdapter(
            engine = engine {
                EmbeddingBatch(
                    vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                    status = rawStatus.copy(backendEvidence = BackendEvidence.NOT_AVAILABLE),
                )
            },
        )
        assertThrows(EmbeddingOutputException::class.java) {
            runBlocking { unavailableEvidence.embedQueries(listOf("query")) }
        }

        val wrongModel = EmbeddingGemma2RuntimeAdapter(
            engine = engine {
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
    fun rejectsOverlongFormattedInputBeforeCallingEngine(): Unit = runBlocking {
        var calls = 0
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine {
                calls++
                error("Engine must not run for an overlong input.")
            },
        )
        val error = assertThrows(EmbeddingOutputException::class.java) {
            runBlocking {
                adapter.embedQueries(listOf("x".repeat(EmbeddingGemma2RuntimeAdapter.MAX_FORMATTED_INPUT_BYTES)))
            }
        }
        assertTrue(error.message!!.contains("split the text"))
        assertEquals(0, calls)
    }
    @Test
    fun batchEmbeds32TextsInFourEngineCallsAndPreservesOrder(): Unit = runBlocking {
        EmbeddingVectorCache.clear()
        var calls = 0
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine { inputs ->
                calls++
                EmbeddingBatch(
                    vectors = inputs.map { input ->
                        val index = Regex("item-(\\d+)").find(input)!!.groupValues[1].toInt() + 1
                        List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { dimension ->
                            when (dimension) { 0 -> index.toFloat(); 1 -> 1f; else -> 0f }
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
    fun emptyBatchDoesNotCallEngine(): Unit = runBlocking {
        var calls = 0
        val adapter = EmbeddingGemma2RuntimeAdapter(engine = engine { calls++; error("Must not infer.") })
        assertTrue(adapter.embedBatch(emptyList()).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun repeatedInputUsesCacheWithoutCallingEngineAgain(): Unit = runBlocking {
        EmbeddingVectorCache.clear()
        var calls = 0
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine { inputs ->
                calls++
                EmbeddingBatch(
                    vectors = inputs.map { List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 0.5f } },
                    status = rawStatus,
                )
            },
        )
        val first = adapter.embedQueries(listOf("same cache input"))
        val second = adapter.embedQueries(listOf("same cache input"))
        assertEquals(1, calls)
        assertEquals(first.vectors, second.vectors)
    }

    @Test
    fun truncatesLongInputAndLogsOriginalAndTruncatedLengths(): Unit = runBlocking {
        EmbeddingVectorCache.clear()
        val events = mutableListOf<Pair<String, String>>()
        var sentInput: String? = null
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine { inputs ->
                sentInput = inputs.single()
                EmbeddingBatch(
                    vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                    status = rawStatus,
                )
            },
            onDiagnostic = { event, detail -> events += event to detail },
        )

        adapter.embedQueries(listOf("x".repeat(3_000)))

        assertEquals(2_000, sentInput!!.substringAfter("| text: ").length)
        assertTrue(events.any { it.first == "embedding.input.truncated" &&
            it.second.contains("originalLength=3000") && it.second.contains("truncatedLength=2000") })
    }

    @Test
    fun cacheKeySeparatesModelAndDimensions() {
        val a = EmbeddingVectorCache.key("same", "model-a", 768)
        val b = EmbeddingVectorCache.key("same", "model-b", 768)
        val c = EmbeddingVectorCache.key("same", "model-a", 256)
        assertTrue(a != b)
        assertTrue(a != c)
    }

    @Test
    fun rejectsInvalidConfiguredBatchSizes() {
        assertTrue(runCatching {
            EmbeddingGemma2RuntimeAdapter(engine = engine { error("Must not infer") }, configuredBatchSize = 0)
        }.isFailure)
        assertTrue(runCatching {
            EmbeddingGemma2RuntimeAdapter(
                engine = engine { error("Must not infer") },
                configuredBatchSize = EmbeddingGemma2RuntimeAdapter.MAX_CONFIGURED_BATCH_SIZE + 1,
            )
        }.isFailure)
    }

    @Test
    fun rejectsTitleCountMismatch(): Unit = runBlocking {
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine { error("Engine must not run when title count is invalid.") },
        )
        assertThrows(EmbeddingOutputException::class.java) {
            runBlocking { adapter.embedDocuments(listOf("a", "b"), listOf("only one")) }
        }
    }
}
