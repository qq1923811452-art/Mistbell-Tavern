package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.TemporalFactObservation
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** Aligns paraphrased facts with an existing active fact so the ledger treats them as duplicates. */
object MemorySemanticDeduplicator {
    suspend fun deduplicateObservations(
        observations: List<TemporalFactObservation>,
        existingCurrent: List<TemporalMemoryFact>,
        embedSimilarity: (suspend (List<String>) -> List<List<Float>>)? = null,
        threshold: Double = DEFAULT_THRESHOLD,
    ): List<TemporalFactObservation> {
        val embed = embedSimilarity
        if (embed == null || observations.isEmpty() || existingCurrent.isEmpty()) return observations
        require(threshold.isFinite() && threshold in -1.0..1.0) { "Similarity threshold is invalid." }
        val existingBySubject = existingCurrent.filter(TemporalMemoryFact::isCurrent).groupBy { subjectKey(it) }
        val replacements = mutableMapOf<Int, Pair<String, String>>()
        for ((key, indexed) in observations.withIndex().groupBy { subjectKey(it.value) }) {
            val existing = existingBySubject[key]
            val newTexts = indexed.map { format(it.value) }
            val vectors =
                existing?.let { subjectFacts ->
                    val existingTexts = subjectFacts.map { format(it) }
                    runCatching { embed(newTexts + existingTexts) }
                        .getOrNull()
                        ?.takeIf { it.size == newTexts.size + existingTexts.size }
                }
            if (existing == null || vectors == null) continue
            indexed.forEachIndexed { localIndex, entry ->
                val newVector = vectors[localIndex]
                val match =
                    existing.indices.firstOrNull { offset ->
                        cosine(newVector, vectors[newTexts.size + offset]) >= threshold
                    }
                if (match != null) {
                    replacements[entry.index] = existing[match].predicate to existing[match].value
                }
            }
        }
        return observations.mapIndexed { index, observation ->
            replacements[index]?.let { (predicate, value) -> observation.copy(predicate = predicate, value = value) }
                ?: observation
        }
    }

    private fun subjectKey(fact: TemporalFactObservation): String {
        return fact.conversationId + "\u0000" + normalize(fact.subject)
    }

    private fun subjectKey(fact: TemporalMemoryFact): String {
        return fact.conversationId + "\u0000" + normalize(fact.subject)
    }

    private fun format(observation: TemporalFactObservation): String =
        "${observation.subject} ${observation.predicate}: ${observation.value}"

    private fun format(fact: TemporalMemoryFact): String = "${fact.subject} ${fact.predicate}: ${fact.value}"

    private fun cosine(
        left: List<Float>,
        right: List<Float>,
    ): Double {
        if (left.size != right.size || left.isEmpty()) return 0.0
        var dot = 0.0
        var leftSquared = 0.0
        var rightSquared = 0.0
        for (index in left.indices) {
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            leftSquared += a * a
            rightSquared += b * b
        }
        val denominator = sqrt(leftSquared * rightSquared)
        return if (denominator == 0.0) 0.0 else (dot / denominator).coerceIn(-1.0, 1.0)
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }

    private const val DEFAULT_THRESHOLD = 0.86
}
