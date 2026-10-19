package com.mistbell.tavern.v3.feature.worldbook

import kotlin.random.Random

/** Keyword activation and budget handling for worldbook entries. */
class LocalWorldBookService(
    private val randomDouble: () -> Double = { Random.nextDouble() },
) {
    fun activateEntries(
        entries: List<WorldBookEntry>,
        scanText: String,
        maxBudget: Int = DEFAULT_MAX_BUDGET,
        recentTexts: List<String> = emptyList(),
        maxDepth: Int = DEFAULT_SCAN_DEPTH,
    ): List<ActivatedWorldBookEntry> {
        require(maxBudget >= 0) { "maxBudget must be non-negative" }
        val corpus = buildScanCorpus(scanText, recentTexts, maxDepth)
        var usedBudget = 0
        val activated = mutableListOf<ActivatedWorldBookEntry>()
        for (entry in entries.sortedBy { it.order }) {
            if (entry.disable) continue
            val matched = if (entry.constant) emptyList() else matchingKeys(entry.keys, corpus)
            if (!entry.constant && matched.isEmpty()) continue
            if (!entry.constant && !passesProbability(entry.probability)) continue
            val size = entry.content.length
            if (usedBudget + size > maxBudget) continue
            activated += ActivatedWorldBookEntry(entry, matched)
            usedBudget += size
        }
        return activated
    }

    fun buildScanCorpus(current: String, recentTexts: List<String>, maxDepth: Int): String =
        buildString {
            appendLine(current)
            recentTexts.takeLast(maxDepth.coerceAtLeast(0)).forEach(::appendLine)
        }

    fun matchingKeys(keys: List<String>, text: String): List<String> {
        val lowerText = text.lowercase()
        return keys.filter { key ->
            val lowerKey = key.lowercase()
            when {
                key.startsWith("/") && key.endsWith("/") && key.length > 2 ->
                    runCatching { Regex(key.substring(1, key.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(text) }
                        .getOrElse { lowerText.contains(lowerKey) }
                lowerKey.contains("*") -> {
                    val expression = lowerKey.split('*').joinToString(".*") { Regex.escape(it) }
                    // Worldbook scan corpora may include newlines; wildcard spans must cross them.
                    runCatching {
                        Regex(
                            expression,
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                        ).containsMatchIn(lowerText)
                    }.getOrElse { lowerText.contains(lowerKey.replace("*", "")) }
                }
                else -> lowerText.contains(lowerKey)
            }
        }
    }

    fun groupByPosition(entries: List<ActivatedWorldBookEntry>): Map<String, List<ActivatedWorldBookEntry>> =
        entries.groupBy { it.entry.insertPosition }

    fun groupByDepth(entries: List<ActivatedWorldBookEntry>): Map<Pair<Int, String>, List<ActivatedWorldBookEntry>> =
        entries.filter { it.entry.depth > 0 }.groupBy { it.entry.depth to it.entry.depthRole }

    fun calculateTotalChars(entries: List<ActivatedWorldBookEntry>): Int =
        entries.sumOf { it.entry.content.length }

    fun buildScanText(userMessage: String, recentMessages: List<String> = emptyList(), maxMessages: Int = 5): String =
        buildString {
            appendLine(userMessage)
            appendLine()
            recentMessages.takeLast(maxMessages.coerceAtLeast(0)).forEach(::appendLine)
        }

    private fun passesProbability(probability: Double): Boolean = when {
        probability >= 1.0 -> true
        probability <= 0.0 -> false
        else -> randomDouble() < probability.coerceIn(0.0, 1.0)
    }

    companion object {
        const val DEFAULT_MAX_BUDGET = 8000
        const val DEFAULT_SCAN_DEPTH = 3
    }
}
