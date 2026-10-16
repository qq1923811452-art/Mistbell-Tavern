package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingGemma2MemoryRuntimeTest {
    @Test
    fun queryAndIndexPassageUseTheirDistinctModelTasks(): Unit = runBlocking {
        val formattedInputs = mutableListOf<String>()
        val engine = object : EmbeddingGemma2InferenceEngine {
            override val runtimeId = "memory-test-engine"

            override suspend fun infer(formattedInputsBatch: List<String>): EmbeddingBatch {
                formattedInputs += formattedInputsBatch
                return EmbeddingBatch(
                    vectors = formattedInputsBatch.map {
                        List(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) { 1f }
                    },
                    status = InferenceRuntimeStatus(
                        phase = InferencePhase.READY,
                        modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
                        backend = InferenceBackend.CPU,
                        runtimeId = runtimeId,
                        backendEvidence = BackendEvidence.RUNTIME_REPORTED,
                    ),
                )
            }
        }
        val runtime = EmbeddingGemma2MemoryRuntime(EmbeddingGemma2RuntimeAdapter(engine))

        val queryVector = runtime.embed("Who left the tavern?")
        val passageVector = runtime.embedPassage("Mira left at midnight.")

        assertEquals("task: search query | text: Who left the tavern?", formattedInputs[0])
        assertEquals(
            "task: search result | title: none | text: Mira left at midnight.",
            formattedInputs[1],
        )
        assertEquals(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS, queryVector.size)
        assertEquals(queryVector.size, passageVector.size)
        assertEquals(EmbeddingGemma2TextPolicy.MODEL_ID, runtime.modelId)
        assertEquals(EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS, runtime.dimensions)
    }
}
