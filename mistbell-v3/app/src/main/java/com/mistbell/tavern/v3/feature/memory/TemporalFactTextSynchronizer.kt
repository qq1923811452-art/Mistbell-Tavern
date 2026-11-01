package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
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

/** Repairs the derived text projection without ever writing an outdated override snapshot. */
class TemporalFactTextSynchronizer(
    private val factStore: TemporalFactPersistence,
    private val memoryTextStore: MemoryTextPersistence,
    private val overrideStore: MemoryOverridePersistence? = null,
) {
    fun synchronize(): TemporalFactTextSyncResult =
        try {
            var result: TemporalFactTextSyncResult = TemporalFactTextSyncResult.NoFacts
            if (overrideStore == null) {
                result = project(emptyList())
            } else {
                // All projection writers acquire the same path lock, then read the latest ledger.
                // Returning the unchanged list does not rewrite the authoritative overrides file.
                overrideStore.update { latest ->
                    result = project(latest)
                    latest
                }
            }
            result
        } catch (_: Exception) {
            TemporalFactTextSyncResult.Skipped(
                "Temporal facts are safe, but effective memory text could not be synchronized.",
            )
        }

    @Suppress("ReturnCount") // Preserve distinct missing/corrupt store outcomes.
    private fun project(overrides: List<MemoryOverride>): TemporalFactTextSyncResult {
        val ledgerWasMissing: Boolean
        val facts =
            when (val loaded = factStore.load()) {
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
        val previous =
            when (val loaded = memoryTextStore.load()) {
                MemoryTextLoadResult.Missing -> emptyList()
                is MemoryTextLoadResult.Loaded -> loaded.records
                is MemoryTextLoadResult.Corrupt -> return TemporalFactTextSyncResult.Skipped(loaded.safeReason)
            }
        val updated =
            memoryTextStore.update { existingTexts ->
                val unrelated = existingTexts.filterNot { it.id.startsWith(FACT_TEXT_ID_PREFIX) }
                (unrelated + EffectiveMemoryResolver.currentTexts(facts, overrides)).distinctBy(MemoryTextRecord::id)
            }
        val changed = updated != previous
        return if (ledgerWasMissing && facts.isEmpty() && !changed) {
            TemporalFactTextSyncResult.NoFacts
        } else {
            TemporalFactTextSyncResult.Synchronized(facts.count { it.isCurrent }, changed)
        }
    }

    companion object {
        const val FACT_TEXT_ID_PREFIX = com.mistbell.tavern.v3.core.memory.MemoryProjection.FACT_TEXT_ID_PREFIX
    }
}
