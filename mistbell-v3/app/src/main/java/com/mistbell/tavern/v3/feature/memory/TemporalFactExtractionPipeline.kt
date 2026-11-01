package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.storage.MemoryOverrideLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryOverridePersistence
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
    /** Manual revisions are re-applied to the projection so extraction never restores edited values. */
    private val overrideStore: MemoryOverridePersistence? = null,
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
                    val overrides = when (val loaded = overrideStore?.load()) {
                        null, MemoryOverrideLoadResult.Missing -> emptyList<MemoryOverride>()
                        is MemoryOverrideLoadResult.Corrupt -> null
                        is MemoryOverrideLoadResult.Loaded -> loaded.overrides
                    }
                    val textUpdate = if (overrides == null) {
                        Result.failure(IllegalStateException("Manual memory revisions could not be read."))
                    } else {
                        runCatching {
                            memoryTextStore.update { existingTexts ->
                                val unrelated = existingTexts.filterNot {
                                    it.id.startsWith(FACT_TEXT_ID_PREFIX)
                                }
                                (unrelated + EffectiveMemoryResolver.currentTexts(facts, overrides))
                                    .distinctBy { it.id }
                            }
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
        const val FACT_TEXT_ID_PREFIX = com.mistbell.tavern.v3.core.memory.MemoryProjection.FACT_TEXT_ID_PREFIX
    }
}
