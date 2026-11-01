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
) {
    init {
        require(scopeId.isNotBlank() && factId.isNotBlank())
        require(subject.isNotBlank() && predicate.isNotBlank() && value.isNotBlank())
        require(subject.length <= MAX_FIELD_CHARS && predicate.length <= MAX_FIELD_CHARS &&
            value.length <= MAX_FIELD_CHARS)
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
    fun of(subject: String, predicate: String): String =
        normalize(subject) + "|" + normalize(predicate)

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
    fun textFor(subject: String, predicate: String, value: String): String = "$subject $predicate: $value"

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
        val byLogicalKey = overrides.groupBy(MemoryOverride::logicalKey)
        val resolved = facts.map { fact ->
            val override = byFactId[fact.id]
                ?: byLogicalKey[MemoryLogicalKey.of(fact.subject, fact.predicate)]
                    ?.filter { it.factId != fact.id }
                    ?.maxByOrNull(MemoryOverride::revision)
            if (override == null) {
                EffectiveMemory(
                    factId = fact.id,
                    subject = fact.subject,
                    predicate = fact.predicate,
                    value = fact.value,
                    origin = MemoryOrigin.AUTOMATIC,
                    revision = 0L,
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

    /** Current effective facts only, in the exact text form prompt retrieval consumes. */
    fun currentTexts(
        facts: List<TemporalMemoryFact>,
        overrides: List<MemoryOverride>,
    ): List<MemoryTextRecord> = resolve(facts, overrides)
        .filter(EffectiveMemory::isCurrent)
        .map { MemoryTextRecord(MemoryProjection.factRecordId(it.factId), it.displayText) }
}
