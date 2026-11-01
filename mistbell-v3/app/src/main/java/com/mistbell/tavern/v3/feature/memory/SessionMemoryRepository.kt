package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EffectiveMemory
import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryLogicalKey
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import com.mistbell.tavern.v3.core.storage.MemoryOverrideLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryOverridePersistence
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence

sealed interface SessionMemoryLoadResult {
    data class Loaded(
        val entries: List<EffectiveMemory>,
        val revision: Long,
        val overrideIssue: String? = null,
    ) : SessionMemoryLoadResult

    data object Missing : SessionMemoryLoadResult

    data class Unavailable(val safeReason: String) : SessionMemoryLoadResult
}

data class MemoryEditDraft(val subject: String, val predicate: String, val value: String)

sealed interface SessionMemorySaveResult {
    data class Saved(
        val entry: EffectiveMemory,
        val revision: Long,
        val projectionWarning: String? = null,
    ) : SessionMemorySaveResult

    data class Conflict(val currentRevision: Long) : SessionMemorySaveResult

    data object NotFound : SessionMemorySaveResult

    data class Invalid(val safeReason: String) : SessionMemorySaveResult

    data class Failed(val safeReason: String) : SessionMemorySaveResult
}

/** Manual revisions never mutate the extraction ledger. All writers use the override path lock. */
class SessionMemoryRepository(
    private val factStore: TemporalFactPersistence,
    private val memoryTextStore: MemoryTextPersistence,
    private val overrideStore: MemoryOverridePersistence,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun load(scopeId: String): SessionMemoryLoadResult {
        val loaded = factStore.load()
        if (loaded is TemporalFactLoadResult.Corrupt) {
            return SessionMemoryLoadResult.Unavailable(loaded.safeReason)
        }
        val facts =
            (loaded as? TemporalFactLoadResult.Loaded)?.facts.orEmpty()
                .filter { it.conversationId == scopeId }
        val overrides = overrideStore.load()
        val items =
            (overrides as? MemoryOverrideLoadResult.Loaded)?.overrides.orEmpty()
                .filter { it.scopeId == scopeId }
        val issue = (overrides as? MemoryOverrideLoadResult.Corrupt)?.safeReason
        return if (facts.isEmpty() && items.isEmpty() && issue == null) {
            SessionMemoryLoadResult.Missing
        } else {
            SessionMemoryLoadResult.Loaded(
                EffectiveMemoryResolver.resolve(facts, items),
                items.maxOfOrNull(MemoryOverride::revision) ?: 0L,
                issue,
            )
        }
    }

    fun saveEdit(
        scopeId: String,
        factId: String,
        expectedRevision: Long,
        draft: MemoryEditDraft,
    ): SessionMemorySaveResult {
        val clean = MemoryEditDraft(draft.subject.trim(), draft.predicate.trim(), draft.value.trim())
        return validateFields(clean) ?: commit(EditRequest(factId, expectedRevision, scopeId, clean))
    }

    /** Keep a revision tombstone: resetting to automatic must not revive stale edit tokens. */
    fun revert(
        factId: String,
        expectedRevision: Long,
    ): SessionMemorySaveResult = commit(EditRequest(factId, expectedRevision))

    private data class EditRequest(
        val factId: String,
        val expectedRevision: Long,
        val scopeId: String? = null,
        val draft: MemoryEditDraft? = null,
    )

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    private fun commit(request: EditRequest): SessionMemorySaveResult {
        var result: SessionMemorySaveResult = SessionMemorySaveResult.NotFound
        try {
            overrideStore.update { existing ->
                val loaded = factStore.load()
                if (loaded is TemporalFactLoadResult.Corrupt) {
                    result = SessionMemorySaveResult.Failed(loaded.safeReason)
                    return@update existing
                }
                val facts = (loaded as? TemporalFactLoadResult.Loaded)?.facts.orEmpty()
                val fact =
                    facts.firstOrNull { it.id == request.factId && it.isCurrent }
                        ?: return@update existing
                if (request.scopeId != null && fact.conversationId != request.scopeId) return@update existing
                val key = MemoryLogicalKey.of(fact.subject, fact.predicate)
                val factsById = facts.associateBy(TemporalMemoryFact::id)
                val group =
                    existing.filter {
                        it.scopeId == fact.conversationId && EffectiveMemoryResolver.originalKey(it, factsById) == key
                    }
                val revision = group.maxOfOrNull(MemoryOverride::revision) ?: 0L
                if (revision != request.expectedRevision) {
                    result = SessionMemorySaveResult.Conflict(revision)
                    return@update existing
                }
                if (revision == Long.MAX_VALUE) {
                    result = SessionMemorySaveResult.Failed("记忆修订号已达上限。")
                    return@update existing
                }
                val draft = request.draft ?: MemoryEditDraft(fact.subject, fact.predicate, fact.value)
                val replacement =
                    MemoryOverride(
                        scopeId = fact.conversationId,
                        factId = fact.id,
                        subject = draft.subject,
                        predicate = draft.predicate,
                        value = draft.value,
                        revision = revision + 1L,
                        updatedAtEpochMillis = clockMillis().coerceAtLeast(0L),
                        sourceLogicalKey = key,
                        reverted = request.draft == null,
                    )
                val updated = existing.filterNot { it in group } + replacement
                val entry = EffectiveMemoryResolver.resolve(facts, updated).first { it.factId == fact.id }
                result = SessionMemorySaveResult.Saved(entry, replacement.revision)
                updated
            }
        } catch (_: IllegalStateException) {
            return SessionMemorySaveResult.Failed("记忆修订存储不可用，原数据已保留。")
        } catch (_: java.io.IOException) {
            return SessionMemorySaveResult.Failed("记忆修订文件写入失败。")
        } catch (_: IllegalArgumentException) {
            return SessionMemorySaveResult.Failed("记忆修订超出存储限制，原数据已保留。")
        }
        val committed = result as? SessionMemorySaveResult.Saved ?: return result
        // Re-read inside the shared override lock; never project the pre-commit facts/overrides.
        val sync = TemporalFactTextSynchronizer(factStore, memoryTextStore, overrideStore).synchronize()
        return committed.copy(
            projectionWarning =
                if (sync is TemporalFactTextSyncResult.Skipped) {
                    "修订已保存，但检索文本尚未同步；同步恢复前不会注入旧记忆。"
                } else {
                    null
                },
        )
    }

    private fun validateFields(draft: MemoryEditDraft): SessionMemorySaveResult.Invalid? {
        val values = listOf(draft.subject, draft.predicate, draft.value)
        return when {
            values.any(String::isEmpty) -> SessionMemorySaveResult.Invalid("主体、关系、内容都不能为空。")
            values.any { it.length > MemoryOverride.MAX_FIELD_CHARS } ->
                SessionMemorySaveResult.Invalid("单个字段最多 ${MemoryOverride.MAX_FIELD_CHARS} 字符。")
            else -> null
        }
    }
}
