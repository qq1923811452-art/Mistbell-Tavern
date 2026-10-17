package com.mistbell.tavern.v3.core.memory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * An immutable, provenance-bearing fact observation. Historical values are retained when a newer
 * observation changes the current value; validUntilEpochMillis is exclusive.
 */
data class TemporalMemoryFact(
    val id: String,
    val conversationId: String,
    val sourceTurnId: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val observedAtEpochMillis: Long,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long? = null,
) {
    init {
        require(id.isNotBlank() && conversationId.isNotBlank() && sourceTurnId.isNotBlank())
        require(subject.isNotBlank() && predicate.isNotBlank() && value.isNotBlank())
        require(observedAtEpochMillis >= 0L && validFromEpochMillis >= 0L)
        require(validUntilEpochMillis == null || validUntilEpochMillis >= validFromEpochMillis)
    }

    val isCurrent: Boolean get() = validUntilEpochMillis == null
}

/** Input shape intentionally mirrors the validated API result without coupling to networking. */
data class TemporalFactObservation(
    val conversationId: String,
    val sourceTurnId: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val observedAtEpochMillis: Long,
) {
    init {
        require(conversationId.isNotBlank() && sourceTurnId.isNotBlank())
        require(subject.isNotBlank() && predicate.isNotBlank() && value.isNotBlank())
        require(observedAtEpochMillis >= 0L)
    }
}

/**
 * Deterministic temporal fact reducer.
 *
 * - Replaying the same observation is idempotent.
 * - Repeating an already-current value does not create duplicate current facts.
 * - A changed value closes the previous fact's validity interval but never deletes its history.
 * - Subject/predicate comparison is Unicode-normalized and case-insensitive.
 *
 * Persistence is deliberately kept outside this pure reducer so callers can commit the complete
 * replacement atomically before updating vector indexes.
 */
object TemporalFactLedger {
    const val MAX_FACTS = 50_000
    private const val MAX_FIELD_CHARS = 2_000

    fun apply(
        existing: List<TemporalMemoryFact>,
        observations: List<TemporalFactObservation>,
    ): List<TemporalMemoryFact> {
        require(existing.size <= MAX_FACTS && observations.size <= MAX_FACTS)
        require(existing.map(TemporalMemoryFact::id).distinct().size == existing.size)
        require(observations.all {
            it.subject.length <= MAX_FIELD_CHARS &&
                it.predicate.length <= MAX_FIELD_CHARS &&
                it.value.length <= MAX_FIELD_CHARS
        })
        data class PredicateKey(val conversationId: String, val subject: String, val predicate: String)

        var facts = existing.toMutableList()
        val knownIds = existing.mapTo(mutableSetOf(), TemporalMemoryFact::id)
        val currentByPredicate = mutableMapOf<PredicateKey, MutableList<Int>>()

        fun key(conversationId: String, subject: String, predicate: String) =
            PredicateKey(conversationId, normalize(subject), normalize(predicate))

        facts.forEachIndexed { index, fact ->
            if (fact.isCurrent) {
                currentByPredicate.getOrPut(key(fact.conversationId, fact.subject, fact.predicate)) { mutableListOf() }
                    .add(index)
            }
        }

        // Stable tie-breaking makes same-millisecond observations deterministic.
        // Precompute IDs once and inspect only current facts for the affected predicate.
        val orderedObservations = observations.map { it to stableId(it) }.sortedWith(
            compareBy<Pair<TemporalFactObservation, String>> { it.first.observedAtEpochMillis }
                .thenBy { it.first.sourceTurnId }
                .thenBy { it.second },
        )
        for ((observation, id) in orderedObservations) {
            if (id in knownIds) continue

            val predicateKey = key(observation.conversationId, observation.subject, observation.predicate)
            val currentIndices = currentByPredicate[predicateKey].orEmpty()
            val currentForPredicate = currentIndices.mapNotNull(facts::getOrNull).filter(TemporalMemoryFact::isCurrent)
            if (currentForPredicate.any { it.validFromEpochMillis >= observation.observedAtEpochMillis }) {
                // A delayed/backdated or same-millisecond extraction must not overwrite a current observation.
                continue
            }
            val valueKey = normalize(observation.value)
            if (currentForPredicate.any { normalize(it.value) == valueKey }) {
                // Same current fact: do not create another active duplicate for another turn.
                continue
            }

            currentIndices.forEach { index ->
                val fact = facts[index]
                if (fact.isCurrent) {
                    facts[index] = fact.copy(
                        validUntilEpochMillis = maxOf(fact.validFromEpochMillis, observation.observedAtEpochMillis),
                    )
                }
            }
            currentByPredicate.remove(predicateKey)

            facts += TemporalMemoryFact(
                id = id,
                conversationId = observation.conversationId,
                sourceTurnId = observation.sourceTurnId,
                subject = observation.subject.trim(),
                predicate = observation.predicate.trim(),
                value = observation.value.trim(),
                observedAtEpochMillis = observation.observedAtEpochMillis,
                validFromEpochMillis = observation.observedAtEpochMillis,
            )
            knownIds.add(id)
            currentByPredicate.getOrPut(predicateKey) { mutableListOf() }.add(facts.lastIndex)
            require(facts.size <= MAX_FACTS) { "Temporal fact ledger exceeds the size limit." }
        }
        return facts.sortedWith(compareBy<TemporalMemoryFact> { it.validFromEpochMillis }.thenBy { it.id })
    }

    fun stableId(observation: TemporalFactObservation): String {
        val fields = listOf(
            observation.conversationId,
            observation.sourceTurnId,
            normalize(observation.subject),
            normalize(observation.predicate),
            normalize(observation.value),
        )
        // Length-prefix each field so embedded separators (including NUL) cannot make two
        // different field tuples produce the same canonical byte sequence.
        val canonical = buildString {
            fields.forEach { field ->
                append(field.length).append(':').append(field)
            }
        }
        return sha256(canonical)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
