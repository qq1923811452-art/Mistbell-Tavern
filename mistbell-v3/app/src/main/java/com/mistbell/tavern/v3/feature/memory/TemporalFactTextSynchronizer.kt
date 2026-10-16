package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult

sealed interface TemporalFactTextSyncResult {
    data object NoFacts : TemporalFactTextSyncResult
    data class Synchronized(val currentFacts: Int, val changed: Boolean) : TemporalFactTextSyncResult
    data class Skipped(val safeReason: String) : TemporalFactTextSyncResult
}

/** Repairs the derived vector-text projection from the durable temporal ledger without touching unrelated memories. */
class TemporalFactTextSynchronizer(
    private val factStore: AtomicTemporalFactStore,
    private val memoryTextStore: AtomicMemoryTextStore,
) {
    fun synchronize(): TemporalFactTextSyncResult {
        val facts = when (val loaded = factStore.load()) {
            TemporalFactLoadResult.Missing -> return TemporalFactTextSyncResult.NoFacts
            is TemporalFactLoadResult.Corrupt -> return TemporalFactTextSyncResult.Skipped(loaded.safeReason)
            is TemporalFactLoadResult.Loaded -> loaded.facts
        }
        val currentFacts = facts.filter { it.isCurrent }
        val previous = when (val loaded = memoryTextStore.load()) {
            MemoryTextLoadResult.Missing -> emptyList()
            is MemoryTextLoadResult.Loaded -> loaded.records
            is MemoryTextLoadResult.Corrupt -> return TemporalFactTextSyncResult.Skipped(loaded.safeReason)
        }
        return try {
            val updated = memoryTextStore.update { existingTexts ->
                val unrelated = existingTexts.filterNot { it.id.startsWith(FACT_TEXT_ID_PREFIX) }
                val factTexts = currentFacts.map { fact ->
                    MemoryTextRecord(
                        id = FACT_TEXT_ID_PREFIX + fact.id,
                        text = "${fact.subject} ${fact.predicate}: ${fact.value}",
                    )
                }
                (unrelated + factTexts).distinctBy(MemoryTextRecord::id)
            }
            TemporalFactTextSyncResult.Synchronized(
                currentFacts = currentFacts.size,
                changed = updated != previous,
            )
        } catch (_: Exception) {
            TemporalFactTextSyncResult.Skipped(
                "Temporal facts are safe, but vector source text could not be synchronized.",
            )
        }
    }

    companion object {
        const val FACT_TEXT_ID_PREFIX = "fact:"
    }
}
