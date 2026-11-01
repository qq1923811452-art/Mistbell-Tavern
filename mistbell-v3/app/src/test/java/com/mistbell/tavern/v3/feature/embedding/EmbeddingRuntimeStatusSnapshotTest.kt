package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingRuntimeStatusSnapshotTest {
    @Test
    fun verifiedEvidenceSurvivesARestartWithoutClaimingNpuExecution() {
        val status = EmbeddingRuntimeStatusSnapshot.restored("google/embeddinggemma-2", "NPU", evidenceVerified = true)
        assertEquals(BackendEvidence.RUNTIME_REPORTED, status.backendEvidence)
        assertEquals(InferenceBackend.UNKNOWN, status.backend)
        assertEquals(InferencePhase.NOT_INITIALIZED, status.phase)
        assertEquals("NPU", status.acceleratorName)
    }

    @Test
    fun missingEvidenceStaysUnverified() {
        val status = EmbeddingRuntimeStatusSnapshot.restored("google/embeddinggemma-2", null, evidenceVerified = false)
        assertEquals(BackendEvidence.NOT_AVAILABLE, status.backendEvidence)
        assertEquals(InferenceBackend.UNKNOWN, status.backend)
        assertNull(status.acceleratorName)
    }

    @Test
    fun idleEngineDoesNotHidePersistedEvidence() {
        val restored = verified()
        assertSame(restored, EmbeddingRuntimeStatusSnapshot.resolve(idle(), idle(), restored))
        assertSame(restored, EmbeddingRuntimeStatusSnapshot.resolve(null, idle(), restored))
    }

    @Test
    fun activeEngineAndFailuresAreNotReplacedByColdStatus() {
        val ready = verified().copy(phase = InferencePhase.READY, backend = InferenceBackend.NPU)
        val failed = idle().copy(phase = InferencePhase.FAILED, lastError = "native failure")
        assertSame(ready, EmbeddingRuntimeStatusSnapshot.resolve(ready, idle(), verified()))
        assertSame(failed, EmbeddingRuntimeStatusSnapshot.resolve(failed, idle(), verified()))
        assertSame(failed, EmbeddingRuntimeStatusSnapshot.resolve(null, failed, verified()))
    }

    @Test
    fun staleModelOrOperationCompletionCannotPublish(): Unit =
        runBlocking {
            val result = CompletableDeferred<com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus>()
            var current = true
            var published = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    EmbeddingRuntimeStatusSnapshot.refresh({ result.await() }, { current }, { published = true })
                }
            current = false
            result.complete(verified())
            job.join()
            assertEquals(false, published)
        }

    @Test
    fun leavingSettingsCancelsEvenNonCooperativeLoader(): Unit =
        runBlocking {
            val result = CompletableDeferred<Unit>()
            var published = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    EmbeddingRuntimeStatusSnapshot.refresh(
                        load = {
                            withContext(NonCancellable) { result.await() }
                            verified()
                        },
                        isCurrent = { true },
                        publish = { published = true },
                    )
                }
            job.cancel()
            result.complete(Unit)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(false, published)
        }

    @Test
    fun missingOrUnreadableModelClearsPreviouslyVerifiedStatus(): Unit =
        runBlocking {
            var status = verified()
            EmbeddingRuntimeStatusSnapshot.refresh({ idle() }, { true }, { status = it })
            assertEquals(idle(), status)
            status = verified()
            EmbeddingRuntimeStatusSnapshot.refresh({ error("model removed") }, { true }, { status = it })
            assertEquals(idle(), status)
        }

    private fun verified() =
        EmbeddingRuntimeStatusSnapshot.restored(
            modelId = "google/embeddinggemma-2",
            acceleratorName = "NPU",
            evidenceVerified = true,
        )

    private fun idle() = com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus.idle()
}
