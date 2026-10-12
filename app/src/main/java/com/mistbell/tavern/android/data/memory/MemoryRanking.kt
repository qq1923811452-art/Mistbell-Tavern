package com.mistbell.tavern.android.data.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Stable, deterministic ranking for prompt-time memory retrieval.
 *
 * Durable profile facts are not aged out. Episodic and transient memories
 * gradually lose priority as they become old, while repeated access provides
 * a small bounded reinforcement. This changes ranking only, never deletes data.
 */
internal object MemoryRanking {
    private val durableTypes = setOf(
        "identity", "character_info", "preference", "relationship", "core", "goal",
    )
    private val transientTypes = setOf("event", "emotion", "note")

    fun score(memory: StructuredMemory, now: Instant = Instant.now()): Double {
        val importance = memory.importance.coerceIn(1, 10).toDouble()
        val type = memory.memoryType.lowercase()
        val ageDays = ageDays(memory.updatedAt, now)
        val decay = when {
            type in durableTypes -> 0.0
            type in transientTypes -> max(0.0, ageDays / 90.0).coerceAtMost(2.5)
            else -> max(0.0, ageDays / 180.0).coerceAtMost(1.5)
        }
        val accessReinforcement = memory.accessCount.coerceAtLeast(0).toDouble()
            .let { kotlin.math.ln(1.0 + it) * 0.15 }
            .coerceAtMost(0.75)
        return importance - decay + accessReinforcement
    }

    private fun ageDays(updatedAt: String, now: Instant): Double =
        try {
            ChronoUnit.SECONDS.between(Instant.parse(updatedAt), now)
                .coerceAtLeast(0L) / 86_400.0
        } catch (_: Exception) {
            0.0
        }
}
