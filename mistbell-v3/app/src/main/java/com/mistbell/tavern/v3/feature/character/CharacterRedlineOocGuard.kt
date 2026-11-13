package com.mistbell.tavern.v3.feature.character

import com.mistbell.tavern.v3.feature.embedding.EmbeddingTaskType
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** Fact-checking guard that flags claims touching an explicit or extracted character red line. */
object CharacterRedlineOocGuard {
    private val evidenceCache = ConcurrentHashMap<String, List<Float>>()
    private val REDLINE_KEYWORDS = listOf("严禁", "禁止", "绝对不", "不可", "红线", "禁忌")

    fun extractRules(card: CharacterCard): List<String> {
        val explicit = card.redlines.map(String::trim).filter(String::isNotEmpty)
        if (explicit.isNotEmpty()) return explicit.take(MAX_RULES)
        val text = card.systemPrompt + "\n" + card.postHistoryInstructions
        return text.split(SENTENCE_BOUNDARY)
            .map(String::trim)
            .filter { sentence -> sentence.isNotEmpty() && REDLINE_KEYWORDS.any(sentence::contains) }
            .map { it.take(MAX_RULE_CHARS) }
            .distinct()
            .take(MAX_RULES)
    }

    suspend fun evaluate(
        card: CharacterCard,
        latestUserMessage: String,
        previousAssistantReply: String?,
        embed: suspend (List<String>, EmbeddingTaskType, List<String?>?) -> List<List<Float>>,
        triggerThreshold: Double = DEFAULT_TRIGGER_THRESHOLD,
    ): String? {
        val rules = extractRules(card)
        if (rules.isEmpty() || latestUserMessage.isBlank()) return null
        val evidence = ensureEvidence(card, rules, embed)
        val claimVector =
            embed(listOf(claimText(latestUserMessage, previousAssistantReply)), EmbeddingTaskType.FACT_CHECKING, null)
                .firstOrNull()
        val claimNorm = claimVector?.let(::norm) ?: 0.0
        val hits =
            if (evidence.isEmpty() || claimVector == null || claimNorm == 0.0) {
                emptyList()
            } else {
                rules.mapNotNull { rule ->
                    val vector = evidence[rule] ?: return@mapNotNull null
                    val score = cosine(claimVector, vector, claimNorm)
                    if (score >= triggerThreshold) rule to score else null
                }.sortedByDescending { it.second }
                    .take(MAX_HITS)
            }
        return if (hits.isEmpty()) {
            null
        } else {
            buildString {
                append("[角色核心铁律 - 绝对禁止违反]")
                hits.forEach { (rule, _) -> append("\n- ").append(rule) }
            }
        }
    }

    private suspend fun ensureEvidence(
        card: CharacterCard,
        rules: List<String>,
        embed: suspend (List<String>, EmbeddingTaskType, List<String?>?) -> List<List<Float>>,
    ): Map<String, List<Float>> {
        val missing = rules.filter { rule -> !evidenceCache.containsKey(evidenceKey(card.name, rule)) }
        if (missing.isNotEmpty()) {
            val vectors =
                embed(
                    missing,
                    EmbeddingTaskType.RETRIEVAL_DOCUMENT,
                    List(missing.size) { card.name },
                )
            if (vectors.size == missing.size) {
                missing.forEachIndexed { index, rule ->
                    evidenceCache[evidenceKey(card.name, rule)] = vectors[index].toList()
                }
            }
        }
        return rules.mapNotNull { rule ->
            evidenceCache[evidenceKey(card.name, rule)]?.let { rule to it }
        }.toMap()
    }

    private fun claimText(
        latestUserMessage: String,
        previousAssistantReply: String?,
    ): String =
        buildString {
            append(latestUserMessage.trim())
            previousAssistantReply?.trim()?.take(MAX_REPLY_CHARS)?.takeIf(String::isNotEmpty)?.let {
                append("\n").append(it)
            }
        }

    private fun evidenceKey(
        cardName: String,
        rule: String,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest((cardName + "\u0000" + rule).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun norm(vector: List<Float>): Double {
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value.toDouble()
        return sqrt(squared)
    }

    private fun cosine(
        left: List<Float>,
        right: List<Float>,
        leftNorm: Double,
    ): Double {
        if (left.size != right.size) return 0.0
        var dot = 0.0
        var rightSquared = 0.0
        for (index in left.indices) {
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            rightSquared += b * b
        }
        val rightNorm = sqrt(rightSquared)
        return if (rightNorm == 0.0) 0.0 else (dot / (leftNorm * rightNorm)).coerceIn(-1.0, 1.0)
    }

    private val SENTENCE_BOUNDARY = Regex("[。！？!?；;\\n]+")
    private const val MAX_RULES = 8
    private const val MAX_RULE_CHARS = 160
    private const val MAX_REPLY_CHARS = 200
    private const val MAX_HITS = 2
    private const val DEFAULT_TRIGGER_THRESHOLD = 0.64
}
