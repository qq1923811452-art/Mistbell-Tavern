package com.mistbell.tavern.v3.feature.worldbook.core

import kotlin.random.Random

/**
 * Deterministic, budgeted worldbook activation.
 *
 * The planner is stateful only through [WorldbookActivationState]; callers keep one state per
 * character/conversation scope so sticky, cooldown and delay are measured in conversation turns.
 * Recursion is bounded by [maxRecursiveDepth] and cycle-safe because an entry can activate once.
 */
class WorldbookPlanner(
    private val randomDouble: () -> Double = { Random.nextDouble() },
) {

    data class Plan(
        val activated: List<ActivatedWorldbookEntry>,
        val droppedByBudget: Int,
    )

    data class ActivationLimits(
        val maxScanDepth: Int = DEFAULT_SCAN_DEPTH,
        val maxBudget: Int = DEFAULT_MAX_BUDGET,
        val maxRecursiveDepth: Int = DEFAULT_RECURSIVE_DEPTH,
    )

    class WorldbookActivationState {
        internal var turn: Int = 0
        internal val lastActivatedTurn = mutableMapOf<String, Int>()
        internal val stickyUntilTurn = mutableMapOf<String, Int>()
        internal val delayedUntilTurn = mutableMapOf<String, Int>()

        fun advanceTurn(): Int {
            turn += 1
            return turn
        }
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "ComplexCondition",
        "ReturnCount",
        "LoopWithTooManyJumpStatements",
    )
    fun activate(
        entries: List<WorldbookEntry>,
        scanText: String,
        recentTexts: List<String> = emptyList(),
        limits: ActivationLimits = ActivationLimits(),
        state: WorldbookActivationState? = null,
    ): Plan {
        require(limits.maxBudget >= 0) { "maxBudget must be non-negative." }
        require(limits.maxScanDepth >= 0) { "maxScanDepth must be non-negative." }
        require(limits.maxRecursiveDepth >= 0) { "maxRecursiveDepth must be non-negative." }

        val turn = state?.advanceTurn() ?: 0
        val ordered = entries.filterNot(WorldbookEntry::disabled).sortedBy(WorldbookEntry::order)
        var corpus = buildCorpus(scanText, recentTexts, limits.maxScanDepth)
        val activated = linkedMapOf<String, ActivatedWorldbookEntry>()
        val activatedGroups = mutableSetOf<String>()
        val droppedByBudget = linkedSetOf<String>()
        var usedBudget = 0

        @Suppress("ReturnCount")
        fun tryActivate(entry: WorldbookEntry, matched: List<String>, carried: Boolean): Boolean {
            if (entry.id in activated) return false
            if (entry.groupName.isNotBlank() && entry.groupName in activatedGroups) return false
            if (!carried && !entry.constant && !passesProbability(entry.probability)) return false
            if (usedBudget + entry.content.length > limits.maxBudget) {
                droppedByBudget += entry.id
                return false
            }
            activated[entry.id] = ActivatedWorldbookEntry(entry, matched, carried)
            usedBudget += entry.content.length
            if (entry.groupName.isNotBlank()) activatedGroups += entry.groupName
            if (state != null && !carried) {
                state.lastActivatedTurn[entry.id] = turn
                state.delayedUntilTurn.remove(entry.id)
                if (entry.sticky) state.stickyUntilTurn[entry.id] = turn + 1
            }
            return true
        }

        ordered.filter(WorldbookEntry::constant).forEach { tryActivate(it, emptyList(), false) }
        corpus += ordered
            .filter { it.constant && it.recursive && it.id in activated }
            .joinToString(separator = "\n") { it.content }

        var recursionDepth = 0
        while (true) {
            val candidates = ordered.filter { entry -> isCandidate(entry, corpus, turn, state, activated.keys) }
            if (candidates.isEmpty()) break
            val newlyActivated = mutableListOf<WorldbookEntry>()
            val groupsClaimedThisPass = mutableSetOf<String>()
            for (entry in candidates) {
                if (entry.groupName.isNotBlank()) {
                    if (entry.groupName in activatedGroups) continue
                    if (!groupsClaimedThisPass.add(entry.groupName)) continue
                }
                val carried = isCarriedBySticky(entry, turn, state)
                val delayedDue = (state?.delayedUntilTurn?.get(entry.id) ?: Int.MAX_VALUE) <= turn
                val matched = matchingKeys(entry.keys, corpus)
                if (!carried && !delayedDue && !entry.constant && entry.delay > 0 &&
                    state != null && entry.id !in state.delayedUntilTurn
                ) {
                    state.delayedUntilTurn[entry.id] = turn + entry.delay
                    continue
                }
                if (tryActivate(entry, matched, carried)) newlyActivated += entry
            }
            if (newlyActivated.isEmpty() || recursionDepth >= limits.maxRecursiveDepth) break
            val recursiveContent = newlyActivated
                .filter(WorldbookEntry::recursive)
                .joinToString(separator = "\n") { it.content }
            if (recursiveContent.isBlank()) break
            corpus += "\n" + recursiveContent
            recursionDepth += 1
        }

        return Plan(activated.values.toList(), droppedByBudget.size)
    }

    fun buildCorpus(current: String, recentTexts: List<String>, maxScanDepth: Int): String = buildString {
        appendLine(current)
        recentTexts.takeLast(maxScanDepth.coerceAtLeast(0)).forEach(::appendLine)
    }

    fun matchingKeys(keys: List<String>, text: String): List<String> {
        val haystack = text.lowercase()
        return keys.filter { key ->
            val lowered = key.lowercase()
            when {
                key.length > 2 && key.startsWith("/") && key.endsWith("/") ->
                    runCatching {
                        Regex(key.substring(1, key.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(text)
                    }.getOrElse { haystack.contains(lowered) }

                lowered.contains('*') -> {
                    val expression = lowered.split('*').joinToString(".*") { Regex.escape(it) }
                    runCatching {
                        Regex(expression, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .containsMatchIn(haystack)
                    }.getOrElse { haystack.contains(lowered.replace("*", "")) }
                }

                else -> haystack.contains(lowered)
            }
        }
    }

    fun groupByPosition(activated: List<ActivatedWorldbookEntry>): Map<String, List<ActivatedWorldbookEntry>> =
        activated.groupBy { it.entry.insertPosition }

    fun groupByDepth(activated: List<ActivatedWorldbookEntry>): Map<Pair<Int, String>, List<ActivatedWorldbookEntry>> =
        activated
            .filter { it.entry.insertPosition == WorldbookPositions.DEPTH || it.entry.depth > 0 }
            .groupBy { it.entry.depth.coerceIn(0, MAX_DEPTH) to it.entry.depthRole }

    fun totalCharacters(activated: List<ActivatedWorldbookEntry>): Int =
        activated.sumOf { it.entry.content.length }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun isCandidate(
        entry: WorldbookEntry,
        corpus: String,
        turn: Int,
        state: WorldbookActivationState?,
        activatedIds: Set<String>,
    ): Boolean {
        if (entry.id in activatedIds) return false
        val carried = isCarriedBySticky(entry, turn, state)
        val delayedUntil = state?.delayedUntilTurn?.get(entry.id) ?: Int.MAX_VALUE
        val delayedDue = delayedUntil <= turn
        val delayPending = (state?.delayedUntilTurn?.get(entry.id) ?: Int.MIN_VALUE) > turn
        val primaryMatch = entry.keys.isNotEmpty() && matchingKeys(entry.keys, corpus).isNotEmpty()
        val secondaryMatch = !entry.requireSecondaryMatch ||
            (entry.secondaryKeys.isNotEmpty() && matchingKeys(entry.secondaryKeys, corpus).isNotEmpty())
        val matchesNow = primaryMatch && secondaryMatch
        val lastActivated = state?.lastActivatedTurn?.get(entry.id) ?: Int.MIN_VALUE
        val cooldownOk = carried || delayedDue || entry.constant ||
            lastActivated == Int.MIN_VALUE || turn - lastActivated > entry.cooldown
        return (carried || delayedDue || (matchesNow && !delayPending)) && cooldownOk
    }

    private fun isCarriedBySticky(entry: WorldbookEntry, turn: Int, state: WorldbookActivationState?): Boolean {
        if (!entry.sticky || state == null) return false
        val last = state.lastActivatedTurn[entry.id] ?: Int.MIN_VALUE
        val until = state.stickyUntilTurn[entry.id] ?: Int.MIN_VALUE
        return turn > last && turn <= until
    }

    private fun passesProbability(probability: Double): Boolean = when {
        probability >= 1.0 -> true
        probability <= 0.0 -> false
        else -> randomDouble() < probability.coerceIn(0.0, 1.0)
    }

    companion object {
        const val DEFAULT_MAX_BUDGET = 8_000
        const val DEFAULT_SCAN_DEPTH = 3
        const val DEFAULT_RECURSIVE_DEPTH = 4
        const val MAX_DEPTH = 10
    }
}
