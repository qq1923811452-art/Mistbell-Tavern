package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.TemporalFactLedger
import com.mistbell.tavern.v3.core.memory.TemporalFactObservation
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence
import okhttp3.Call

sealed interface TemporalFactPipelineOutcome {
    data class Saved(
        val extractedFacts: Int,
        val currentFacts: Int,
        val indexedTextRecords: Int,
        val indexingWarning: String? = null,
    ) : TemporalFactPipelineOutcome

    data class Failed(val safeMessage: String, val httpStatus: Int? = null) : TemporalFactPipelineOutcome
}

/**
 * Persists validated extraction output as an append-preserving temporal ledger, then mirrors
 * current facts into the vector source-text store. Failure is surfaced and must not fail chat.
 */
class TemporalFactExtractionPipeline(
    private val client: MemoryExtractionClient,
    private val factStore: TemporalFactPersistence,
    private val memoryTextStore: MemoryTextPersistence,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun extractAndPersist(
        input: MemoryExtractionInput,
        commitGate: (() -> Unit) -> Unit = { it() },
        observer: (TemporalFactPipelineOutcome) -> Unit,
    ): Call = client.extract(input, MemoryExtractionObserver { outcome ->
        commitGate commit@{
            when (outcome) {
                is MemoryExtractionOutcome.Failure -> observer(
                    TemporalFactPipelineOutcome.Failed(outcome.safeMessage, outcome.httpStatus),
                )
                is MemoryExtractionOutcome.Success -> {
                    val persisted = runCatching { persistFacts(input, outcome.result) }
                    if (persisted.isFailure) {
                        observer(
                            TemporalFactPipelineOutcome.Failed(
                                "Extracted facts could not be safely persisted; existing history was preserved.",
                            ),
                        )
                        return@commit
                    }

                    val facts = persisted.getOrThrow()
                    val currentFacts = facts.filter { it.isCurrent }
                    val textUpdate = runCatching {
                        memoryTextStore.update { existingTexts ->
                            val unrelated = existingTexts.filterNot { it.id.startsWith(FACT_TEXT_ID_PREFIX) }
                            val currentFactTexts = currentFacts.map { fact ->
                                MemoryTextRecord(
                                    id = FACT_TEXT_ID_PREFIX + fact.id,
                                    text = "${fact.subject} ${fact.predicate}: ${fact.value}",
                                )
                            }
                            (unrelated + currentFactTexts).distinctBy(MemoryTextRecord::id)
                        }
                    }
                    observer(
                        TemporalFactPipelineOutcome.Saved(
                            extractedFacts = outcome.result.facts.size,
                            currentFacts = currentFacts.size,
                            indexedTextRecords = textUpdate.getOrNull()?.count {
                                it.id.startsWith(FACT_TEXT_ID_PREFIX)
                            } ?: 0,
                            indexingWarning = if (textUpdate.isFailure) {
                                "Facts were saved, but vector source text could not be updated; local index refresh is required."
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
        }
    })

    private fun persistFacts(
        input: MemoryExtractionInput,
        result: MemoryExtractionResult,
    ): List<TemporalMemoryFact> = factStore.update { existing ->
        val observedAt = clockMillis().also { require(it >= 0L) }
        val observations = result.facts.map { fact ->
            TemporalFactObservation(
                conversationId = input.conversationId,
                sourceTurnId = fact.sourceTurnId,
                subject = fact.subject,
                predicate = fact.predicate,
                value = fact.value,
                observedAtEpochMillis = observedAt,
            )
        }
        TemporalFactLedger.apply(existing, observations)
    }

    companion object {
        const val FACT_TEXT_ID_PREFIX = "fact:"
    }
}
