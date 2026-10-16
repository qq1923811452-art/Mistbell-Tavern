package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.memory.FactStatus
import com.mistbell.tavern.v3.core.memory.MemoryBatchPlan
import com.mistbell.tavern.v3.core.memory.MemoryFact
import com.mistbell.tavern.v3.core.memory.MemoryJobLedger
import com.mistbell.tavern.v3.core.memory.MemoryJobStatus
import com.mistbell.tavern.v3.core.storage.MemoryFactHistoryLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryFactHistoryPersistence
import com.mistbell.tavern.v3.core.storage.MemoryJobLedgerLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryJobLedgerPersistence
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MemoryExtractionPipelineTest {
    @Test
    fun completedTurnsPersistBatchBeforeWorkerCommitsFacts() {
        val ledgerStore = InMemoryLedger()
        val coordinator = MemoryTurnCompletionCoordinator(ledgerStore)
        val turns = (1..3).map { EligibleConversationTurn("turn-$it", it.toLong()) }

        assertTrue(coordinator.onTurnsCompleted("conversation-1", turns) is MemoryEnqueueResult.Enqueued)
        assertEquals(MemoryJobStatus.PENDING, ledgerStore.ledger().jobs.single().status)

        val factStore = InMemoryFacts()
        val worker = worker(ledgerStore, factStore, now = { 1_000L })
        var result: MemoryPipelineResult? = null
        worker.processNext { result = it }

        assertEquals(MemoryPipelineResult.Completed(ledgerStore.ledger().jobs.single().plan.id, 1), result)
        assertEquals(MemoryJobStatus.COMPLETED, ledgerStore.ledger().jobs.single().status)
        assertEquals("tea", factStore.history().single().value)
        assertEquals("turn-2", factStore.history().single().sourceTurnId)
    }

    @Test
    fun retryAfterFactCommitDoesNotDuplicateHistory() {
        val ledgerStore = InMemoryLedger()
        val plan = plan()
        ledgerStore.save(MemoryJobLedger().enqueue(plan))
        val factStore = InMemoryFacts()
        factStore.seedSave()
        var now = 1_000L
        val worker = worker(ledgerStore, factStore, now = { now })
        var first: MemoryPipelineResult? = null
        worker.processNext { first = it }

        assertTrue(first is MemoryPipelineResult.Failed)
        assertEquals(1, factStore.history().size)
        assertEquals(MemoryJobStatus.RETRY_WAIT, ledgerStore.ledger().jobs.single().status)

        now += 30_000L
        var second: MemoryPipelineResult? = null
        worker.processNext { second = it }

        assertTrue(second is MemoryPipelineResult.Completed)
        assertEquals(1, factStore.history().size)
        assertEquals(MemoryJobStatus.COMPLETED, ledgerStore.ledger().jobs.single().status)
    }

    @Test
    fun rejectsFactSourceOutsideTheClaimedBatch() {
        val ledgerStore = InMemoryLedger()
        ledgerStore.save(MemoryJobLedger().enqueue(plan()))
        val factStore = InMemoryFacts()
        val extractor = ImmediateExtractor(
            listOf(ExtractedFactDraft("user", "likes", "tea", "not-in-batch")),
        )
        val worker = MemoryExtractionWorker(
            ledgerStore,
            factStore,
            extractor,
            MemoryTurnTextSource { _, _ -> "source text" },
            nowEpochMillis = { 1_000L },
        )
        var result: MemoryPipelineResult? = null
        worker.processNext { result = it }

        assertTrue(result is MemoryPipelineResult.Failed)
        assertTrue(factStore.history().isEmpty())
        assertEquals(MemoryJobStatus.RETRY_WAIT, ledgerStore.ledger().jobs.single().status)
    }

    private fun worker(
        ledgerStore: InMemoryLedger,
        factStore: InMemoryFacts,
        now: () -> Long,
    ) = MemoryExtractionWorker(
        ledgerStore = ledgerStore,
        factStore = factStore,
        extractor = ImmediateExtractor(
            listOf(ExtractedFactDraft("user", "likes", "tea", "turn-2")),
        ),
        textSource = MemoryTurnTextSource { _, _ -> "The user likes tea." },
        nowEpochMillis = now,
    )

    private fun plan() = MemoryBatchPlan(
        id = "memory:conversation-1:turn-1:turn-3",
        conversationId = "conversation-1",
        turns = (1..3).map { EligibleConversationTurn("turn-$it", it.toLong()) },
    )

    private class ImmediateExtractor(
        private val facts: List<ExtractedFactDraft>,
    ) : MemoryFactExtractor {
        private val call = OkHttpClient().newCall(
            Request.Builder().url("http://localhost/unused").build(),
        )

        override fun extract(
            input: MemoryExtractionInput,
            observer: MemoryExtractionObserver,
        ): Call {
            observer.onFinished(MemoryExtractionOutcome.Success(MemoryExtractionResult(facts)))
            return call
        }
    }

    private class InMemoryLedger : MemoryJobLedgerPersistence {
        private var value: MemoryJobLedgerLoadResult = MemoryJobLedgerLoadResult.Missing
        private var failCompletedSaveOnce = true

        override fun save(ledger: MemoryJobLedger) {
            if (failCompletedSaveOnce && ledger.jobs.any { it.status == MemoryJobStatus.COMPLETED }) {
                failCompletedSaveOnce = false
                throw IOException("simulated crash window")
            }
            value = MemoryJobLedgerLoadResult.Loaded(ledger)
        }

        override fun load(): MemoryJobLedgerLoadResult = value

        fun ledger(): MemoryJobLedger =
            (value as MemoryJobLedgerLoadResult.Loaded).ledger
    }

    private class InMemoryFacts : MemoryFactHistoryPersistence {
        private var value: MemoryFactHistoryLoadResult = MemoryFactHistoryLoadResult.Missing
        private var seeded = false

        override fun save(history: List<MemoryFact>) {
            value = MemoryFactHistoryLoadResult.Loaded(history.toList())
        }

        override fun load(): MemoryFactHistoryLoadResult = value

        fun history(): List<MemoryFact> =
            (value as? MemoryFactHistoryLoadResult.Loaded)?.history.orEmpty()

        fun seedSave() {
            seeded = true
        }
    }
}
