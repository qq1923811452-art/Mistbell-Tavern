package com.mistbell.tavern.android.data.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

/**
 * Stable, deterministic ranking for prompt-time memory retrieval.
 *
 * Durable profile facts are not aged out. Episodic and transient memories
 * gradually lose priority as they become old, while repeated access provides
 * a small bounded reinforcement. This changes ranking only, never deletes data.
 */
internal object MemoryRanking {
    private const val SECONDS_PER_DAY = 86_400.0
    private const val EPISODIC_DECAY_DAYS = 90.0
    private const val EPISODIC_MAX_DECAY = 2.5
    private const val DEFAULT_DECAY_DAYS = 180.0
    private const val DEFAULT_MAX_DECAY = 1.5
    private const val ACCESS_REINFORCEMENT_FACTOR = 0.15
    private const val MAX_ACCESS_REINFORCEMENT = 0.75
    private const val MIN_IMPORTANCE = 1
    private const val MAX_IMPORTANCE = 10

    private val durableTypes =
        setOf(
            "identity",
            "character_info",
            "preference",
            "relationship",
            "core",
            "goal",
        )
    private val transientTypes = setOf("event", "emotion", "note")

    fun score(
        memory: StructuredMemory,
        now: Instant = Instant.now(),
    ): Double {
        val importance = memory.importance.coerceIn(MIN_IMPORTANCE, MAX_IMPORTANCE).toDouble()
        val type = memory.memoryType.lowercase(Locale.ROOT)
        val ageDays = ageDays(memory.updatedAt, now)
        val decay =
            when {
                type in durableTypes -> 0.0
                type in transientTypes -> max(0.0, ageDays / EPISODIC_DECAY_DAYS).coerceAtMost(EPISODIC_MAX_DECAY)
                else -> max(0.0, ageDays / DEFAULT_DECAY_DAYS).coerceAtMost(DEFAULT_MAX_DECAY)
            }
        val accessReinforcement =
            memory.accessCount.coerceAtLeast(0).toDouble()
                .let { kotlin.math.ln(1.0 + it) * ACCESS_REINFORCEMENT_FACTOR }
                .coerceAtMost(MAX_ACCESS_REINFORCEMENT)
        return importance - decay + accessReinforcement
    }

    private fun ageDays(
        updatedAt: String,
        now: Instant,
    ): Double =
        try {
            ChronoUnit.SECONDS.between(Instant.parse(updatedAt), now)
                .coerceAtLeast(0L) / SECONDS_PER_DAY
        } catch (_: Exception) {
            0.0
        }
}
