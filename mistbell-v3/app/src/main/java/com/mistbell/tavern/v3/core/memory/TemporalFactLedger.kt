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
        var facts = existing.toMutableList()
        for (observation in observations.sortedBy(TemporalFactObservation::observedAtEpochMillis)) {
            val id = stableId(observation)
            if (facts.any { it.id == id }) continue

            val subjectKey = normalize(observation.subject)
            val predicateKey = normalize(observation.predicate)
            val currentForPredicate = facts.filter {
                it.isCurrent &&
                    it.conversationId == observation.conversationId &&
                    normalize(it.subject) == subjectKey &&
                    normalize(it.predicate) == predicateKey
            }
            if (currentForPredicate.any { it.validFromEpochMillis > observation.observedAtEpochMillis }) {
                // A delayed/backdated extraction must not overwrite a newer current observation.
                continue
            }
            if (currentForPredicate.any { normalize(it.value) == normalize(observation.value) }) {
                // Same current fact: do not create another active duplicate for another turn.
                continue
            }

            facts = facts.map { fact ->
                if (fact.isCurrent &&
                    fact.conversationId == observation.conversationId &&
                    normalize(fact.subject) == subjectKey &&
                    normalize(fact.predicate) == predicateKey
                ) {
                    fact.copy(validUntilEpochMillis = maxOf(fact.validFromEpochMillis, observation.observedAtEpochMillis))
                } else {
                    fact
                }
            }.toMutableList()

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
