package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.memory.FactHistoryReducer
import com.mistbell.tavern.v3.core.memory.MemoryBatchPolicy
import com.mistbell.tavern.v3.core.memory.MemoryBatchPlan
import com.mistbell.tavern.v3.core.memory.MemoryFact
import com.mistbell.tavern.v3.core.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.core.memory.MemoryJobLedger
import com.mistbell.tavern.v3.core.memory.MemoryJobStatus
import com.mistbell.tavern.v3.core.storage.MemoryFactHistoryLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryFactHistoryPersistence
import com.mistbell.tavern.v3.core.storage.MemoryJobLedgerLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryJobLedgerPersistence
import okhttp3.Call
import java.nio.ByteBuffer
import java.security.MessageDigest

fun interface MemoryFactExtractor {
    fun extract(input: MemoryExtractionInput, observer: MemoryExtractionObserver): Call
}

fun interface MemoryTurnTextSource {
    fun textForTurn(conversationId: String, turnId: String): String?
}

sealed interface MemoryEnqueueResult {
    data class Enqueued(val plan: MemoryBatchPlan) : MemoryEnqueueResult
    data object NoBatchReady : MemoryEnqueueResult
    data class Failure(val safeMessage: String) : MemoryEnqueueResult
}

/**
 * Called after a completed chat exchange is durably stored. It creates a stable three-turn plan
 * and persists it before any extraction request can be dispatched.
 */
class MemoryTurnCompletionCoordinator(
    private val ledgerStore: MemoryJobLedgerPersistence,
) {
    @Synchronized
    fun onTurnsCompleted(
        conversationId: String,
        orderedTurns: List<EligibleConversationTurn>,
    ): MemoryEnqueueResult {
        val ledger = when (val loaded = ledgerStore.load()) {
            MemoryJobLedgerLoadResult.Missing -> MemoryJobLedger()
            is MemoryJobLedgerLoadResult.Loaded -> loaded.ledger
            is MemoryJobLedgerLoadResult.Corrupt -> return MemoryEnqueueResult.Failure(loaded.safeReason)
        }
        val claimedTurnIds = ledger.jobs
            .filter { it.status != MemoryJobStatus.PERMANENT_FAILURE }
            .flatMap { it.plan.turns }
            .map(EligibleConversationTurn::id)
            .toSet()
        val plan = try {
            MemoryBatchPolicy.nextBatch(
                conversationId = conversationId,
                orderedTurns = orderedTurns,
                claimedTurnIds = claimedTurnIds,
            ) ?: return MemoryEnqueueResult.NoBatchReady
        } catch (_: IllegalArgumentException) {
            return MemoryEnqueueResult.Failure("Completed turns failed memory-batch validation.")
        }
        return try {
            ledgerStore.save(ledger.enqueue(plan))
            MemoryEnqueueResult.Enqueued(plan)
        } catch (_: Exception) {
            MemoryEnqueueResult.Failure("Memory job could not be saved.")
        }
    }
}

sealed interface MemoryPipelineResult {
    data object NoWork : MemoryPipelineResult
    data class Completed(val planId: String, val insertedFacts: Int) : MemoryPipelineResult
    data class Failed(val safeMessage: String) : MemoryPipelineResult
}

/**
 * Processes one persisted job at a time. The claim is saved before dispatch. Facts are committed
 * before the ledger is marked complete; replay after a crash is safe because fact IDs are stable
 * and the fact-history reducer treats matching IDs/payloads as idempotent.
 */
class MemoryExtractionWorker(
    private val ledgerStore: MemoryJobLedgerPersistence,
    private val factStore: MemoryFactHistoryPersistence,
    private val extractor: MemoryFactExtractor,
    private val textSource: MemoryTurnTextSource,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private var activeJobId: String? = null

    @Synchronized
    fun processNext(observer: (MemoryPipelineResult) -> Unit): Call? {
        if (activeJobId != null) {
            observer(MemoryPipelineResult.NoWork)
            return null
        }
        val ledger = when (val loaded = ledgerStore.load()) {
            MemoryJobLedgerLoadResult.Missing -> {
                observer(MemoryPipelineResult.NoWork)
                return null
            }
            is MemoryJobLedgerLoadResult.Loaded -> loaded.ledger
            is MemoryJobLedgerLoadResult.Corrupt -> {
                observer(MemoryPipelineResult.Failed(loaded.safeReason))
                return null
            }
        }
        val (claimedLedger, job) = ledger.claimNext(nowEpochMillis())
        if (job == null) {
            observer(MemoryPipelineResult.NoWork)
            return null
        }
        try {
            ledgerStore.save(claimedLedger)
            activeJobId = job.plan.id
        } catch (_: Exception) {
            observer(MemoryPipelineResult.Failed("Memory job claim could not be saved."))
            return null
        }

        val texts = LinkedHashMap<String, String>()
        for (turn in job.plan.turns) {
            val text = try {
                textSource.textForTurn(job.plan.conversationId, turn.id)
            } catch (_: Exception) {
                null
            }
            if (text.isNullOrBlank()) {
                val failure = failJob(
                    job.plan.id,
                    "A source conversation turn is unavailable.",
                    permanent = true,
                )
                synchronized(this) { activeJobId = null }
                observer(failure)
                return null
            }
            texts[turn.id] = text
        }

        return try {
            extractor.extract(
                MemoryExtractionInput(
                    conversationId = job.plan.conversationId,
                    turns = job.plan.turns,
                    textByTurnId = texts,
                ),
            ) { outcome ->
                val result = try {
                    when (outcome) {
                        is MemoryExtractionOutcome.Failure -> failJob(job.plan.id, outcome.safeMessage)
                        is MemoryExtractionOutcome.Success -> finishJob(job.plan, outcome.result)
                    }
                } finally {
                    synchronized(this) { activeJobId = null }
                }
                observer(result)
            }
        } catch (_: Exception) {
            val failure = failJob(job.plan.id, "Memory-extraction request could not be started.")
            synchronized(this) { activeJobId = null }
            observer(failure)
            null
        }
    }

    private fun finishJob(plan: MemoryBatchPlan, result: MemoryExtractionResult): MemoryPipelineResult {
        return try {
            val allowedTurnIds = plan.turns.map(EligibleConversationTurn::id).toSet()
            var insertedFacts = 0
            factStore.update { currentHistory ->
                var updatedHistory = currentHistory
                result.facts.forEach { draft ->
                    require(draft.sourceTurnId in allowedTurnIds) {
                        "Extracted fact references a turn outside its job."
                    }
                    val fact = MemoryFact(
                        id = stableFactId(plan.conversationId, draft),
                        subject = draft.subject,
                        predicate = draft.predicate,
                        value = draft.value,
                        sourceTurnId = draft.sourceTurnId,
                        recordedAtEpochMillis = nowEpochMillis(),
                    )
                    val update = FactHistoryReducer.append(updatedHistory, fact)
                    updatedHistory = update.history
                    if (update.inserted) insertedFacts++
                }
                updatedHistory
            }

            // Completion is deliberately written only after the entire fact history was saved.
            val currentLedger = when (val loaded = ledgerStore.load()) {
                is MemoryJobLedgerLoadResult.Loaded -> loaded.ledger
                MemoryJobLedgerLoadResult.Missing ->
                    throw IllegalStateException("Memory job ledger disappeared during extraction.")
                is MemoryJobLedgerLoadResult.Corrupt ->
                    throw IllegalStateException(loaded.safeReason)
            }
            val completed = currentLedger.complete(plan.id, nowEpochMillis())
            ledgerStore.save(completed)
            MemoryPipelineResult.Completed(plan.id, insertedFacts)
        } catch (_: Exception) {
            failJob(plan.id, "Extracted facts could not be safely committed.")
        }
    }

    private fun failJob(
        planId: String,
        safeMessage: String,
        permanent: Boolean = false,
    ): MemoryPipelineResult {
        return try {
            val currentLedger = when (val loaded = ledgerStore.load()) {
                is MemoryJobLedgerLoadResult.Loaded -> loaded.ledger
                MemoryJobLedgerLoadResult.Missing ->
                    return MemoryPipelineResult.Failed("Memory job ledger is unavailable.")
                is MemoryJobLedgerLoadResult.Corrupt ->
                    return MemoryPipelineResult.Failed(loaded.safeReason)
            }
            val job = currentLedger.jobs.firstOrNull { it.plan.id == planId }
                ?: return MemoryPipelineResult.Failed("Memory job is no longer available.")
            if (job.status != MemoryJobStatus.PROCESSING) {
                return MemoryPipelineResult.Failed("Memory job is not in a processing state.")
            }
            val boundedMessage = safeMessage.take(MemoryExtractionJob.MAX_ERROR_LENGTH)
                .ifBlank { "Memory extraction failed." }
            val updated = if (permanent) {
                currentLedger.failPermanently(planId, boundedMessage)
            } else {
                val attempt = (job.attemptCount - 1).coerceIn(0, 10)
                val delay = minOf(30_000L * (1L shl attempt), MAX_RETRY_DELAY_MILLIS)
                currentLedger.retry(
                    planId = planId,
                    nowEpochMillis = nowEpochMillis(),
                    delayMillis = delay,
                    safeError = boundedMessage,
                )
            }
            ledgerStore.save(updated)
            MemoryPipelineResult.Failed(boundedMessage)
        } catch (_: Exception) {
            MemoryPipelineResult.Failed("Memory job failure state could not be saved.")
        }
    }

    private fun stableFactId(conversationId: String, fact: ExtractedFactDraft): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(conversationId, fact.sourceTurnId, fact.subject, fact.predicate, fact.value).forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return "fact:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val MAX_RETRY_DELAY_MILLIS = 6 * 60 * 60 * 1000L
    }
}
