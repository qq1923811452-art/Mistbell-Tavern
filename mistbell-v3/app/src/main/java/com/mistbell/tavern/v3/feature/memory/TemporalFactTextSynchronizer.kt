package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.storage.MemoryOverrideLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryOverridePersistence
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence

sealed interface TemporalFactTextSyncResult {
    data object NoFacts : TemporalFactTextSyncResult
    data class Synchronized(val currentFacts: Int, val changed: Boolean) : TemporalFactTextSyncResult
    data class Skipped(val safeReason: String) : TemporalFactTextSyncResult
}

/** Repairs the derived vector-text projection from the durable temporal ledger without touching unrelated memories. */
class TemporalFactTextSynchronizer(
    private val factStore: TemporalFactPersistence,
    private val memoryTextStore: MemoryTextPersistence,
    /** User revisions must be re-applied on every pass, otherwise synchronization erases them. */
    private val overrideStore: MemoryOverridePersistence? = null,
) {
    fun synchronize(): TemporalFactTextSyncResult {
        val ledgerWasMissing: Boolean
        val facts = when (val loaded = factStore.load()) {
            TemporalFactLoadResult.Missing -> {
                ledgerWasMissing = true
                emptyList()
            }
            is TemporalFactLoadResult.Corrupt -> return TemporalFactTextSyncResult.Skipped(loaded.safeReason)
            is TemporalFactLoadResult.Loaded -> {
                ledgerWasMissing = false
                loaded.facts
            }
        }
        val overrides = when (val loaded = overrideStore?.load()) {
            null, MemoryOverrideLoadResult.Missing -> emptyList<MemoryOverride>()
            is MemoryOverrideLoadResult.Corrupt ->
                return TemporalFactTextSyncResult.Skipped("Manual memory revisions could not be read.")
            is MemoryOverrideLoadResult.Loaded -> loaded.overrides
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
                val factTexts = EffectiveMemoryResolver.currentTexts(facts, overrides)
                (unrelated + factTexts).distinctBy(MemoryTextRecord::id)
            }
            val changed = updated != previous
            if (ledgerWasMissing && currentFacts.isEmpty() && !changed) {
                TemporalFactTextSyncResult.NoFacts
            } else {
                TemporalFactTextSyncResult.Synchronized(
                    currentFacts = currentFacts.size,
                    changed = changed,
                )
            }
        } catch (_: Exception) {
            TemporalFactTextSyncResult.Skipped(
                "Temporal facts are safe, but vector source text could not be synchronized.",
            )
        }
    }

    companion object {
        const val FACT_TEXT_ID_PREFIX = com.mistbell.tavern.v3.core.memory.MemoryProjection.FACT_TEXT_ID_PREFIX
    }
}
