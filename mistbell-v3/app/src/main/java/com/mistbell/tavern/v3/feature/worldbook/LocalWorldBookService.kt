package com.mistbell.tavern.v3.feature.worldbook

import com.mistbell.tavern.v3.feature.worldbook.core.WorldbookEntry
import com.mistbell.tavern.v3.feature.worldbook.core.WorldbookPlanner
import kotlin.random.Random

/**
 * Per-conversation activation history. Keep one instance per character/session scope.
 * Sticky entries carry for one subsequent turn; delay and cooldown are measured in turns.
 */
class WorldBookActivationState internal constructor() {
    internal val plannerState = WorldbookPlanner.WorldbookActivationState()

    internal var turn: Int
        get() = plannerState.turn
        set(value) {
            plannerState.turn = value
        }

    internal val lastActivatedTurn: MutableMap<String, Int>
        get() = plannerState.lastActivatedTurn

    internal val stickyUntilTurn: MutableMap<String, Int>
        get() = plannerState.stickyUntilTurn

    internal val delayedUntilTurn: MutableMap<String, Int>
        get() = plannerState.delayedUntilTurn

    internal fun advanceTurn(): Int = plannerState.advanceTurn()
}

data class WorldBookActivationReport(
    val activatedEntries: List<ActivatedWorldBookEntry>,
    val droppedByBudget: Int,
)

/** Keyword activation and budget handling, backed by the independent 3.0 planner. */
class LocalWorldBookService(
    private val randomDouble: () -> Double = { Random.nextDouble() },
) {
    private val planner = WorldbookPlanner(randomDouble)

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
        val limits = WorldbookPlanner.ActivationLimits(
            maxScanDepth = maxDepth,
            maxBudget = maxBudget,
            maxRecursiveDepth = maxRecursiveDepth,
        )
        val plan = planner.activate(
            entries = entries.mapNotNull(::toPlannerEntry),
            scanText = scanText,
            recentTexts = recentTexts,
            limits = limits,
            state = state?.plannerState,
        )
        return WorldBookActivationReport(
            activatedEntries = plan.activated.map { activated ->
                ActivatedWorldBookEntry(fromPlannerEntry(activated.entry), activated.matchedKeys)
            },
            droppedByBudget = plan.droppedByBudget,
        )
    }

    fun matchingKeys(keys: List<String>, text: String): List<String> = planner.matchingKeys(keys, text)

    fun buildScanCorpus(
        current: String,
        recentTexts: List<String> = emptyList(),
        maxDepth: Int = DEFAULT_SCAN_DEPTH,
    ): String = planner.buildCorpus(current, recentTexts, maxDepth)

    fun groupByPlacement(entries: List<ActivatedWorldBookEntry>): Map<String, List<ActivatedWorldBookEntry>> =
        entries.groupBy { it.entry.insertPosition }

    fun groupByDepth(entries: List<ActivatedWorldBookEntry>): Map<Pair<Int, String>, List<ActivatedWorldBookEntry>> =
        entries
            .filter { it.entry.insertPosition == WorldBookPlacement.POSITION_DEPTH || it.entry.depth > 0 }
            .groupBy { it.entry.depth.coerceIn(0, 10) to it.entry.depthRole }

    fun calculateTotalChars(entries: List<ActivatedWorldBookEntry>): Int =
        entries.sumOf { it.entry.content.length }

    fun buildScanText(userMessage: String, recentMessages: List<String> = emptyList(), maxMessages: Int = 5): String =
        buildString {
            appendLine(userMessage)
            appendLine()
            recentMessages.takeLast(maxMessages.coerceAtLeast(0)).forEach(::appendLine)
        }

    private fun toPlannerEntry(entry: WorldBookEntry): WorldbookEntry? {
        val content = entry.content
        if (content.isBlank()) return null
        return WorldbookEntry(
            id = entry.id,
            comment = entry.comment,
            keys = entry.keys,
            secondaryKeys = entry.secondaryKeys,
            requireSecondaryMatch = entry.requireSecondaryMatch,
            recursive = entry.recursive,
            content = content,
            constant = entry.constant,
            disabled = entry.disable,
            order = entry.order,
            insertPosition = entry.insertPosition,
            depth = entry.depth,
            probability = entry.probability,
            depthRole = entry.depthRole,
            sticky = entry.sticky,
            cooldown = entry.cooldown,
            delay = entry.delay,
            groupName = entry.groupName,
        )
    }

    private fun fromPlannerEntry(entry: WorldbookEntry): WorldBookEntry = WorldBookEntry(
        id = entry.id,
        bookId = "",
        comment = entry.comment,
        keys = entry.keys,
        secondaryKeys = entry.secondaryKeys,
        requireSecondaryMatch = entry.requireSecondaryMatch,
        recursive = entry.recursive,
        content = entry.content,
        constant = entry.constant,
        disable = entry.disabled,
        order = entry.order,
        insertPosition = entry.insertPosition,
        depth = entry.depth,
        probability = entry.probability,
        depthRole = entry.depthRole,
        sticky = entry.sticky,
        cooldown = entry.cooldown,
        delay = entry.delay,
        groupName = entry.groupName,
    )

    companion object {
        const val DEFAULT_MAX_BUDGET = 8_000
        const val DEFAULT_SCAN_DEPTH = 3
        const val DEFAULT_RECURSIVE_DEPTH = 4
    }
}
