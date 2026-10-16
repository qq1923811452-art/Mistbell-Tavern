package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingGemma2RuntimeAdapterTest {
    private val rawStatus = InferenceRuntimeStatus(
        phase = InferencePhase.READY,
        modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
        backend = InferenceBackend.CPU,
        runtimeId = "fake-litert-engine",
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

        assertEquals("task: search result | query: Who left the tavern?", seen[0][0])
        assertEquals("title: Night 1 | text: Mira left at midnight.", seen[1][0])
        assertEquals(128, queryResult.vectors.single().size)
        assertEquals(128, documentResult.vectors.single().size)
        assertEquals(InferenceBackend.CPU, queryResult.status.backend)
        assertEquals(adapter.runtimeId, queryResult.status.runtimeId)
    }

    @Test
    fun rejectsUnknownBackendAndWrongModel(): Unit = runBlocking {
        val unknownBackend = EmbeddingGemma2RuntimeAdapter(
            engine = engine {
                EmbeddingBatch(
                    vectors = listOf(List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }),
                    status = rawStatus.copy(backend = InferenceBackend.UNKNOWN),
                )
            },
        )
        assertThrows(EmbeddingOutputException::class.java) {
            runBlocking { unknownBackend.embedQueries(listOf("query")) }
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
    fun rejectsTitleCountMismatch(): Unit = runBlocking {
        val adapter = EmbeddingGemma2RuntimeAdapter(
            engine = engine { error("Engine must not run when title count is invalid.") },
        )
        assertThrows(EmbeddingOutputException::class.java) {
            runBlocking { adapter.embedDocuments(listOf("a", "b"), listOf("only one")) }
        }
    }
}
