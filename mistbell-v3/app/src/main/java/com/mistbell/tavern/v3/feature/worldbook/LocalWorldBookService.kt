package com.mistbell.tavern.v3.feature.worldbook

import kotlin.random.Random

/**
 * Per-conversation activation history. Keep one instance per character/session scope.
 * Sticky entries carry for one subsequent turn; delay and cooldown are measured in turns.
 */
class WorldBookActivationState internal constructor() {
    internal var turn: Int = 0
    internal val lastActivatedTurn = mutableMapOf<String, Int>()
    internal val stickyUntilTurn = mutableMapOf<String, Int>()
    internal val delayedUntilTurn = mutableMapOf<String, Int>()

    internal fun advanceTurn(): Int {
        turn += 1
        return turn
    }
}

data class WorldBookActivationReport(
    val activatedEntries: List<ActivatedWorldBookEntry>,
    val droppedByBudget: Int,
)

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
        state: WorldBookActivationState? = null,
    ): List<ActivatedWorldBookEntry> = activateEntriesWithReport(
        entries, scanText, maxBudget, recentTexts, maxDepth, maxRecursiveDepth, state,
    ).activatedEntries

    fun activateEntriesWithReport(
        entries: List<WorldBookEntry>,
        scanText: String,
        maxBudget: Int = DEFAULT_MAX_BUDGET,
        recentTexts: List<String> = emptyList(),
        maxDepth: Int = DEFAULT_SCAN_DEPTH,
        maxRecursiveDepth: Int = DEFAULT_RECURSIVE_DEPTH,
        state: WorldBookActivationState? = null,
    ): WorldBookActivationReport {
        require(maxBudget >= 0) { "maxBudget must be non-negative" }
        require(maxRecursiveDepth >= 0) { "maxRecursiveDepth must be non-negative" }
        val turn = state?.advanceTurn() ?: 0
        val ordered = entries.filterNot { it.disable }.sortedBy { it.order }
        var corpus = buildScanCorpus(scanText, recentTexts, maxDepth)
        val activated = linkedMapOf<String, ActivatedWorldBookEntry>()
        val activatedGroups = mutableSetOf<String>()
        val droppedByBudgetIds = mutableSetOf<String>()
        var usedBudget = 0

        fun activate(entry: WorldBookEntry, matched: List<String>, carriedSticky: Boolean = false): Boolean {
            if (entry.id in activated) return false
            if (entry.groupName.isNotBlank() && entry.groupName in activatedGroups) return false
            if (!carriedSticky && !entry.constant && !passesProbability(entry.probability)) return false
            val size = entry.content.length
            if (usedBudget + size > maxBudget) {
                droppedByBudgetIds += entry.id
                return false
            }
            activated[entry.id] = ActivatedWorldBookEntry(entry, matched)
            usedBudget += size
            if (entry.groupName.isNotBlank()) activatedGroups += entry.groupName
            if (state != null && !carriedSticky) {
                state.lastActivatedTurn[entry.id] = turn
                state.delayedUntilTurn.remove(entry.id)
                if (entry.sticky) state.stickyUntilTurn[entry.id] = turn + 1
            }
            return true
        }

        // Constant entries are included regardless of keywords, matching the original PR #60 policy.
        ordered.filter { it.constant }.forEach { activate(it, emptyList()) }
        corpus += ordered.filter { it.constant && it.recursive && it.id in activated }
            .joinToString(separator = "\n") { it.content }

        var recursiveDepth = 0
        while (true) {
            val candidates = ordered.filter { entry ->
                if (entry.id in activated) return@filter false
                val stickyUntil = state?.stickyUntilTurn?.get(entry.id) ?: Int.MIN_VALUE
                val lastActivated = state?.lastActivatedTurn?.get(entry.id) ?: Int.MIN_VALUE
                val carriedSticky = entry.sticky && turn > lastActivated && turn <= stickyUntil
                val delayedDue = (state?.delayedUntilTurn?.get(entry.id) ?: Int.MAX_VALUE) <= turn
                val primaryMatch = entry.keys.isNotEmpty() && matchingKeys(entry.keys, corpus).isNotEmpty()
                val secondaryMatch = !entry.requireSecondaryMatch ||
                    (entry.secondaryKeys.isNotEmpty() && matchingKeys(entry.secondaryKeys, corpus).isNotEmpty())
                val matchesNow = primaryMatch && secondaryMatch
                val delayPending = (state?.delayedUntilTurn?.get(entry.id) ?: Int.MIN_VALUE) > turn
                (carriedSticky || delayedDue || (matchesNow && !delayPending)) &&
                    (carriedSticky || delayedDue || entry.constant ||
                        lastActivated == Int.MIN_VALUE || turn - lastActivated > entry.cooldown)
            }

            if (candidates.isEmpty()) break
            val newlyActivated = mutableListOf<WorldBookEntry>()
            val groupsSeenThisPass = mutableSetOf<String>()
            for (entry in candidates) {
                if (entry.groupName.isNotBlank() &&
                    (entry.groupName in activatedGroups || !groupsSeenThisPass.add(entry.groupName))) {
                    continue
                }
                val lastActivated = state?.lastActivatedTurn?.get(entry.id) ?: Int.MIN_VALUE
                val carriedSticky = entry.sticky && turn > lastActivated &&
                    turn <= (state?.stickyUntilTurn?.get(entry.id) ?: Int.MIN_VALUE)
                val delayedDue = (state?.delayedUntilTurn?.get(entry.id) ?: Int.MAX_VALUE) <= turn
                val matched = matchingKeys(entry.keys, corpus)
                if (!carriedSticky && !delayedDue && !entry.constant && entry.delay > 0 &&
                    state != null && entry.id !in state.delayedUntilTurn) {
                    state.delayedUntilTurn[entry.id] = turn + entry.delay
                    continue
                }
                if (activate(entry, matched, carriedSticky)) newlyActivated += entry
            }
            if (newlyActivated.isEmpty() || recursiveDepth >= maxRecursiveDepth) break
            val recursiveContent = newlyActivated.filter { it.recursive }.joinToString("\n") { it.content }
            if (recursiveContent.isBlank()) break
            corpus += "\n" + recursiveContent
            recursiveDepth += 1
        }
        return WorldBookActivationReport(
            activatedEntries = activated.values.toList(),
            droppedByBudget = droppedByBudgetIds.size,
        )
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
                    // Scan corpora are newline-terminated; avoid a whole-string match that fails on that newline.
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
        entries.filter {
            it.entry.insertPosition == WorldBookPlacement.POSITION_DEPTH || it.entry.depth > 0
        }.groupBy { it.entry.depth.coerceIn(0, 10) to it.entry.depthRole }

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
