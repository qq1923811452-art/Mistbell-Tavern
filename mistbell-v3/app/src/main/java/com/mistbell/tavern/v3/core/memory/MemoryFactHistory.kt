package com.mistbell.tavern.v3.core.memory

import java.util.Locale

/**
 * Immutable historical fact. Superseded records remain in history and must not be used as
 * active prompt facts. Timestamps are supplied by the caller so this policy is deterministic.
 */
data class MemoryFact(
    val id: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val sourceTurnId: String,
    val recordedAtEpochMillis: Long,
    val status: FactStatus = FactStatus.ACTIVE,
    val supersededAtEpochMillis: Long? = null,
    val supersededByFactId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Fact ID must not be blank." }
        require(subject.isNotBlank()) { "Fact subject must not be blank." }
        require(predicate.isNotBlank()) { "Fact predicate must not be blank." }
        require(value.isNotBlank()) { "Fact value must not be blank." }
        require(sourceTurnId.isNotBlank()) { "Source turn ID must not be blank." }
        require(recordedAtEpochMillis >= 0) { "Recorded timestamp must not be negative." }
        require(
            (status == FactStatus.ACTIVE &&
                supersededAtEpochMillis == null &&
                supersededByFactId == null) ||
                (status == FactStatus.SUPERSEDED &&
                    supersededAtEpochMillis != null &&
                    !supersededByFactId.isNullOrBlank()),
        ) { "Superseded metadata must agree with fact status." }
    }
}

enum class FactStatus {
    ACTIVE,
    SUPERSEDED,
}

data class FactHistoryUpdate(
    val history: List<MemoryFact>,
    val inserted: Boolean,
    val supersededFactIds: List<String>,
)

/**
 * Pure reducer for temporal facts. Callers persist the returned complete history atomically.
 * Replaying an identical fact ID is idempotent; reusing an ID for different content is rejected.
 */
object FactHistoryReducer {
    fun append(history: List<MemoryFact>, incoming: MemoryFact): FactHistoryUpdate {
        val existingById = history.firstOrNull { it.id == incoming.id }
        if (existingById != null) {
            require(existingById == incoming) {
                "Fact ID '${incoming.id}' already exists with different content."
            }
            return FactHistoryUpdate(history.toList(), inserted = false, supersededFactIds = emptyList())
        }

        val incomingKey = key(incoming.subject, incoming.predicate)
        val equivalentActive = history.firstOrNull {
            it.status == FactStatus.ACTIVE &&
                key(it.subject, it.predicate) == incomingKey &&
                normalize(it.value) == normalize(incoming.value)
        }
        if (equivalentActive != null) {
            // The same currently-known value is already represented. Do not create duplicate facts.
            return FactHistoryUpdate(history.toList(), inserted = false, supersededFactIds = emptyList())
        }

        val conflicts = history.filter {
            it.status == FactStatus.ACTIVE && key(it.subject, it.predicate) == incomingKey
        }
        val superseded = conflicts.map { fact ->
            fact.copy(
                status = FactStatus.SUPERSEDED,
                supersededAtEpochMillis = incoming.recordedAtEpochMillis,
                supersededByFactId = incoming.id,
            )
        }
        val supersededById = superseded.associateBy(MemoryFact::id)
        val updatedHistory = history.map { supersededById[it.id] ?: it } + incoming
        return FactHistoryUpdate(
            history = updatedHistory,
            inserted = true,
            supersededFactIds = superseded.map(MemoryFact::id),
        )
    }

    fun activeFacts(history: List<MemoryFact>): List<MemoryFact> =
        history.filter { it.status == FactStatus.ACTIVE }

    private fun key(subject: String, predicate: String): Pair<String, String> =
        normalize(subject) to normalize(predicate)

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
}
