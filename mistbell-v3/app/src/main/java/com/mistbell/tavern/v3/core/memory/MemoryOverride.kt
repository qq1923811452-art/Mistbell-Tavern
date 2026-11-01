package com.mistbell.tavern.v3.core.memory

import java.text.Normalizer
import java.util.Locale

/**
 * A user revision layered over one automatically extracted fact.
 *
 * The original ledger entry stays untouched so the extraction provenance (source turn, AI value,
 * observation time) is never rewritten by a person editing text. The revision counter is the
 * optimistic-concurrency token: a save must present the revision it saw, otherwise the write is
 * rejected instead of silently overwriting a newer edit.
 */
data class MemoryOverride(
    val scopeId: String,
    val factId: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    /** Original ledger identity, not the editable wording. Null supports the version-1 format. */
    val sourceLogicalKey: String? = null,
    /** A reset retains its revision, so an old edit can never pass a reused token (ABA). */
    val reverted: Boolean = false,
) {
    init {
        require(scopeId.isNotBlank() && factId.isNotBlank())
        require(subject.isNotBlank() && predicate.isNotBlank() && value.isNotBlank())
        require(
            subject.length <= MAX_FIELD_CHARS && predicate.length <= MAX_FIELD_CHARS &&
                value.length <= MAX_FIELD_CHARS,
        )
        require(revision >= 1L && updatedAtEpochMillis >= 0L)
    }

    /** Same key the ledger uses, so a re-extracted fact with a new ID keeps the user's wording. */
    val logicalKey: String get() = MemoryLogicalKey.of(subject, predicate)

    companion object {
        /** Mirrors TemporalFactLedger.MAX_FIELD_CHARS; a manual edit may not exceed the ledger limit. */
        const val MAX_FIELD_CHARS = 2_000
    }
}

/** Normalization shared with the fact ledger: NFKC, trimmed, case-folded with the root locale. */
object MemoryLogicalKey {
    /** Scope is already implied by the per-scope store; adding it would only risk aliasing bugs. */
    fun of(
        subject: String,
        predicate: String,
    ): String {
        val normalizedSubject = normalize(subject)
        return "${normalizedSubject.length}:$normalizedSubject${normalize(predicate)}"
    }

    fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}

enum class MemoryOrigin { AUTOMATIC, MANUAL }

/** What the user sees and what prompt retrieval uses, after applying overrides over the ledger. */
data class EffectiveMemory(
    val factId: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val origin: MemoryOrigin,
    val revision: Long,
    val sourceTurnId: String,
    val observedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long?,
    val isCurrent: Boolean,
) {
    val displayText: String get() = MemoryProjection.textFor(subject, predicate, value)
}

/** One projection used by UI, text storage and prompt retrieval; never re-implemented per caller. */
object MemoryProjection {
    fun textFor(
        subject: String,
        predicate: String,
        value: String,
    ): String = "$subject $predicate: $value"

    fun factRecordId(factId: String): String = FACT_TEXT_ID_PREFIX + factId

    const val FACT_TEXT_ID_PREFIX = "fact:"
}

object EffectiveMemoryResolver {
    /**
     * Overrides match a fact by ID first, then by logical key. The key fallback is what stops a
     * re-extracted value from resurfacing under a brand-new fact ID after the user corrected it.
     */
    fun resolve(
        facts: List<TemporalMemoryFact>,
        overrides: List<MemoryOverride>,
    ): List<EffectiveMemory> {
        val byFactId = overrides.associateBy(MemoryOverride::factId)
        val factsById = facts.associateBy(TemporalMemoryFact::id)
        val byLogicalKey = overrides.groupBy { originalKey(it, factsById) }
        val resolved =
            facts.map { fact ->
                val override =
                    if (fact.isCurrent) {
                        byLogicalKey[MemoryLogicalKey.of(fact.subject, fact.predicate)]
                            ?.filter { it.scopeId == fact.conversationId }
                            ?.maxByOrNull(MemoryOverride::revision)
                    } else {
                        byFactId[fact.id]?.takeIf { it.scopeId == fact.conversationId }
                    }
                if (override == null || override.reverted) {
                    EffectiveMemory(
                        factId = fact.id,
                        subject = fact.subject,
                        predicate = fact.predicate,
                        value = fact.value,
                        origin = MemoryOrigin.AUTOMATIC,
                        revision = override?.revision ?: 0L,
                        sourceTurnId = fact.sourceTurnId,
                        observedAtEpochMillis = fact.observedAtEpochMillis,
                        updatedAtEpochMillis = null,
                        isCurrent = fact.isCurrent,
                    )
                } else {
                    EffectiveMemory(
                        factId = fact.id,
                        subject = override.subject,
                        predicate = override.predicate,
                        value = override.value,
                        origin = MemoryOrigin.MANUAL,
                        revision = override.revision,
                        sourceTurnId = fact.sourceTurnId,
                        observedAtEpochMillis = fact.observedAtEpochMillis,
                        updatedAtEpochMillis = override.updatedAtEpochMillis,
                        isCurrent = fact.isCurrent,
                    )
                }
            }
        return resolved.sortedWith(
            compareByDescending<EffectiveMemory> { it.isCurrent }
                .thenByDescending { it.updatedAtEpochMillis ?: it.observedAtEpochMillis }
                .thenBy { it.factId },
        )
    }

    fun originalKey(
        override: MemoryOverride,
        factsById: Map<String, TemporalMemoryFact>,
    ): String =
        override.sourceLogicalKey ?: factsById[override.factId]?.let {
            MemoryLogicalKey.of(it.subject, it.predicate)
        } ?: override.logicalKey

    /** Current effective facts only, in the exact text form prompt retrieval consumes. */
    fun currentTexts(
        facts: List<TemporalMemoryFact>,
        overrides: List<MemoryOverride>,
    ): List<MemoryTextRecord> =
        resolve(facts, overrides)
            .filter(EffectiveMemory::isCurrent)
            .map { MemoryTextRecord(MemoryProjection.factRecordId(it.factId), it.displayText) }
}
