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
        maxRecursiveDepth: Int = DEFAULT_RECURSIVE_DEPTH,
    ): List<ActivatedWorldBookEntry> {
        require(maxBudget >= 0) { "maxBudget must be non-negative" }
        require(maxRecursiveDepth >= 0) { "maxRecursiveDepth must be non-negative" }
        val ordered = entries.filterNot { it.disable }.sortedBy { it.order }
        var corpus = buildScanCorpus(scanText, recentTexts, maxDepth)
        val activated = linkedMapOf<String, ActivatedWorldBookEntry>()
        val activatedGroups = mutableSetOf<String>()
        var usedBudget = 0

        fun activate(entry: WorldBookEntry, matched: List<String>): Boolean {
            if (entry.id in activated) return false
            if (entry.groupName.isNotBlank() && entry.groupName in activatedGroups) return false
            if (!entry.constant && !passesProbability(entry.probability)) return false
            val size = entry.content.length
            if (usedBudget + size > maxBudget) return false
            activated[entry.id] = ActivatedWorldBookEntry(entry, matched)
            usedBudget += size
            if (entry.groupName.isNotBlank()) activatedGroups += entry.groupName
            return true
        }

        // Constant entries are included regardless of keywords, matching the original PR #60 policy.
        ordered.filter { it.constant }.forEach { activate(it, emptyList()) }
        corpus += ordered.filter { it.constant && it.recursive && it.id in activated }
            .joinToString(separator = "\n") { it.content }

        var recursiveDepth = 0
        while (true) {
            val candidates = ordered.filter { entry ->
                entry.id !in activated &&
                    entry.keys.isNotEmpty() &&
                    matchingKeys(entry.keys, corpus).isNotEmpty() &&
                    (!entry.requireSecondaryMatch ||
                        (entry.secondaryKeys.isNotEmpty() && matchingKeys(entry.secondaryKeys, corpus).isNotEmpty()))
            }
            if (candidates.isEmpty()) break

            val newlyActivated = mutableListOf<WorldBookEntry>()
            val groupsSeenThisPass = mutableSetOf<String>()
            for (entry in candidates) {
                if (entry.groupName.isNotBlank() &&
                    (entry.groupName in activatedGroups || entry.groupName in groupsSeenThisPass)) {
                    continue
                }
                val matched = matchingKeys(entry.keys, corpus)
                if (activate(entry, matched)) {
                    newlyActivated += entry
                    if (entry.groupName.isNotBlank()) groupsSeenThisPass += entry.groupName
                }
            }
            if (newlyActivated.isEmpty() || recursiveDepth >= maxRecursiveDepth) break
            val recursiveContent = newlyActivated.filter { it.recursive }.joinToString("\n") { it.content }
            if (recursiveContent.isBlank()) break
            corpus += "\n" + recursiveContent
            recursiveDepth += 1
        }
        return activated.values.toList()
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
        const val DEFAULT_RECURSIVE_DEPTH = 4
    }
}
