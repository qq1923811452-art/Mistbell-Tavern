package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EffectiveMemory
import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.memory.MemoryProjection
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import com.mistbell.tavern.v3.core.storage.MemoryOverrideLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryOverridePersistence
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence

sealed interface SessionMemoryLoadResult {
    data class Loaded(
        val entries: List<EffectiveMemory>,
        /** Highest override revision for this scope; the UI uses it as the optimistic token. */
        val revision: Long,
        val overrideIssue: String? = null,
    ) : SessionMemoryLoadResult

    data object Missing : SessionMemoryLoadResult
    data class Unavailable(val safeReason: String) : SessionMemoryLoadResult
}

/** The three editable fields travel together so the write path keeps a single draft object. */
data class MemoryEditDraft(val subject: String, val predicate: String, val value: String)

sealed interface SessionMemorySaveResult {
    data class Saved(val entry: EffectiveMemory, val revision: Long) : SessionMemorySaveResult

    /** Someone else wrote this fact first; the caller must reload and let the user re-apply. */
    data class Conflict(val currentRevision: Long) : SessionMemorySaveResult
    data object NotFound : SessionMemorySaveResult
    data class Invalid(val safeReason: String) : SessionMemorySaveResult
    data class Failed(val safeReason: String) : SessionMemorySaveResult
}

/**
 * Single write path for manual memory edits.
 *
 * The authoritative commit is the override record; the searchable text projection is derived
 * afterwards from the merged view. A failure between the two leaves the edit saved and the text
 * stale, and the next synchronizer pass repairs it, so the two files never need a fake transaction.
 */
class SessionMemoryRepository(
    private val factStore: TemporalFactPersistence,
    private val memoryTextStore: MemoryTextPersistence,
    private val overrideStore: MemoryOverridePersistence,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun load(scopeId: String): SessionMemoryLoadResult {
        val loadedFacts = factStore.load()
        if (loadedFacts is TemporalFactLoadResult.Corrupt) {
            return SessionMemoryLoadResult.Unavailable(loadedFacts.safeReason)
        }
        val facts = (loadedFacts as? TemporalFactLoadResult.Loaded)?.facts.orEmpty()
        val overrides = readOverrides(scopeId)
        val empty = facts.isEmpty() && overrides.items.isEmpty() && overrides.issue == null
        return if (empty) {
            SessionMemoryLoadResult.Missing
        } else {
            SessionMemoryLoadResult.Loaded(
                entries = EffectiveMemoryResolver.resolve(facts, overrides.items),
                revision = overrides.items.maxOfOrNull(MemoryOverride::revision) ?: 0L,
                overrideIssue = overrides.issue,
            )
        }
    }

    @Suppress("ReturnCount") // Guard clauses keep every rejection reason explicit on the write path.
    fun saveEdit(
        scopeId: String,
        factId: String,
        expectedRevision: Long,
        draft: MemoryEditDraft,
    ): SessionMemorySaveResult {
        val cleanSubject = draft.subject.trim()
        val cleanPredicate = draft.predicate.trim()
        val cleanValue = draft.value.trim()
        validateFields(cleanSubject, cleanPredicate, cleanValue)?.let { return it }

        val facts = when (val loaded = factStore.load()) {
            TemporalFactLoadResult.Missing -> return SessionMemorySaveResult.NotFound
            is TemporalFactLoadResult.Corrupt -> return SessionMemorySaveResult.Failed(loaded.safeReason)
            is TemporalFactLoadResult.Loaded -> loaded.facts
        }
        val fact = facts.firstOrNull { it.id == factId } ?: return SessionMemorySaveResult.NotFound
        if (!fact.isCurrent) return SessionMemorySaveResult.NotFound

        val now = clockMillis().coerceAtLeast(0L)
        return updateOverrides(factId, expectedRevision) { existing ->
            existing.filterNot { it.factId == factId } + MemoryOverride(
                scopeId = scopeId,
                factId = factId,
                subject = cleanSubject,
                predicate = cleanPredicate,
                value = cleanValue,
                revision = expectedRevision + 1L,
                updatedAtEpochMillis = now,
            )
        }.let { outcome ->
            when (outcome) {
                is OverrideWrite.Conflict -> SessionMemorySaveResult.Conflict(outcome.currentRevision)
                is OverrideWrite.Failed -> SessionMemorySaveResult.Failed(outcome.safeReason)
                is OverrideWrite.Written -> {
                    projectTexts(facts, outcome.overrides)
                    val entry = EffectiveMemoryResolver.resolve(facts, outcome.overrides)
                        .firstOrNull { it.factId == factId }
                        ?: return SessionMemorySaveResult.Failed("记忆修订未能保存。")
                    SessionMemorySaveResult.Saved(entry, expectedRevision + 1L)
                }
            }
        }
    }

    /** Restores the automatic value by deleting the override instead of rewriting the ledger. */
    @Suppress("ReturnCount")
    fun revert(factId: String, expectedRevision: Long): SessionMemorySaveResult {
        val facts = when (val loaded = factStore.load()) {
            TemporalFactLoadResult.Missing -> return SessionMemorySaveResult.NotFound
            is TemporalFactLoadResult.Corrupt -> return SessionMemorySaveResult.Failed(loaded.safeReason)
            is TemporalFactLoadResult.Loaded -> loaded.facts
        }
        if (facts.none { it.id == factId }) return SessionMemorySaveResult.NotFound
        return updateOverrides(factId, expectedRevision) { existing ->
            existing.filterNot { it.factId == factId }
        }.let { outcome ->
            when (outcome) {
                is OverrideWrite.Conflict -> SessionMemorySaveResult.Conflict(outcome.currentRevision)
                is OverrideWrite.Failed -> SessionMemorySaveResult.Failed(outcome.safeReason)
                is OverrideWrite.Written -> {
                    projectTexts(facts, outcome.overrides)
                    val entry = EffectiveMemoryResolver.resolve(facts, outcome.overrides)
                        .firstOrNull { it.factId == factId }
                        ?: return SessionMemorySaveResult.NotFound
                    SessionMemorySaveResult.Saved(entry, 0L)
                }
            }
        }
    }

    private fun validateFields(subject: String, predicate: String, value: String): SessionMemorySaveResult.Invalid? {
        val limit = MemoryOverride.MAX_FIELD_CHARS
        val tooLong = subject.length > limit || predicate.length > limit || value.length > limit
        return when {
            subject.isEmpty() || predicate.isEmpty() || value.isEmpty() ->
                SessionMemorySaveResult.Invalid("主体、关系、内容都不能为空。")
            tooLong -> SessionMemorySaveResult.Invalid("单个字段最多 $limit 字符。")
            else -> null
        }
    }

    private sealed interface OverrideWrite {
        data class Written(val overrides: List<MemoryOverride>) : OverrideWrite
        data class Conflict(val currentRevision: Long) : OverrideWrite
        data class Failed(val safeReason: String) : OverrideWrite
    }

    private fun updateOverrides(
        factId: String,
        expectedRevision: Long,
        transform: (List<MemoryOverride>) -> List<MemoryOverride>,
    ): OverrideWrite {
        var conflict: Long? = null
        return try {
            val written = overrideStore.update { existing ->
                val currentRevision = existing.firstOrNull { it.factId == factId }?.revision ?: 0L
                if (currentRevision != expectedRevision) {
                    conflict = currentRevision
                    return@update existing
                }
                transform(existing)
            }
            conflict?.let(OverrideWrite::Conflict) ?: OverrideWrite.Written(written)
        } catch (error: IllegalStateException) {
            OverrideWrite.Failed(error.message ?: "记忆修订存储不可用。")
        } catch (error: java.io.IOException) {
            OverrideWrite.Failed(error.javaClass.simpleName)
        }
    }

    /** Derived projection; a failure here never rolls back the already-saved override. */
    private fun projectTexts(facts: List<TemporalMemoryFact>, overrides: List<MemoryOverride>) {
        runCatching {
            val projected = EffectiveMemoryResolver.currentTexts(facts, overrides)
            memoryTextStore.update { existing ->
                existing.filterNot { it.id.startsWith(MemoryProjection.FACT_TEXT_ID_PREFIX) } + projected
            }
        }
    }

    private data class Overrides(val items: List<MemoryOverride>, val issue: String?)

    private fun readOverrides(scopeId: String): Overrides = when (val loaded = overrideStore.load()) {
        MemoryOverrideLoadResult.Missing -> Overrides(emptyList(), null)
        is MemoryOverrideLoadResult.Corrupt -> Overrides(emptyList(), loaded.safeReason)
        is MemoryOverrideLoadResult.Loaded ->
            Overrides(loaded.overrides.filter { it.scopeId == scopeId }, null)
    }
}
