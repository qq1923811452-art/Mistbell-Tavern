package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingOutputValidatorTest {
    private val model = EmbeddingModelSpec("embeddinggemma-2-candidate", dimensions = 3, maxBatchSize = 4)
    private val runtime = object : LocalEmbeddingRuntime {
        override val model = this@EmbeddingOutputValidatorTest.model
        override val runtimeId = "test-runtime"
        override suspend fun embed(texts: List<String>) = result(texts.size)
    }

    private fun result(count: Int, dimensions: Int = 3, backend: InferenceBackend = InferenceBackend.CPU) =
        EmbeddingBatch(
            vectors = List(count) { List(dimensions) { 0.25f } },
            status = InferenceRuntimeStatus(
                phase = InferencePhase.READY,
                modelId = model.modelId,
                backend = backend,
                runtimeId = "test-runtime",
            ),
        )

    @Test
    fun acceptsMatchingFiniteVectorsAndVerifiedRuntime() {
        val validated = EmbeddingOutputValidator.validate(runtime, listOf("hello", "world"), result(2))
        assertEquals(2, validated.vectors.size)
        assertEquals(3, validated.dimensions)
        assertEquals(InferenceBackend.CPU, validated.status.backend)
    }

    @Test
    fun rejectsCountMismatch() {
        assertThrows(EmbeddingOutputException::class.java) {
            EmbeddingOutputValidator.validate(runtime, listOf("hello", "world"), result(1))
        }
    }

    @Test
    fun rejectsDimensionMismatch() {
        assertThrows(EmbeddingOutputException::class.java) {
            EmbeddingOutputValidator.validate(runtime, listOf("hello"), result(1, dimensions = 2))
        }
    }

    @Test
    fun rejectsUnverifiedBackend() {
        assertThrows(EmbeddingOutputException::class.java) {
            EmbeddingOutputValidator.validate(runtime, listOf("hello"), result(1, backend = InferenceBackend.UNKNOWN))
        }
    }

    @Test
    fun rejectsBlankInput() {
        assertThrows(EmbeddingOutputException::class.java) {
            EmbeddingOutputValidator.validate(runtime, listOf("  "), result(1))
        }
    }
}
