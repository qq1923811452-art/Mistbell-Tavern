package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.character.CharacterCard
import com.mistbell.tavern.v3.feature.worldbook.LocalWorldBookService
import com.mistbell.tavern.v3.feature.worldbook.WorldBookActivationState
import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry
import com.mistbell.tavern.v3.feature.worldbook.WorldBookParser

/**
 * Owns the existing per-turn worldbook activation and activation-state persistence.
 */
internal class ChatWorldBookActivationCoordinator(
    private val settings: SharedPreferences,
    private val diagnosticStore: DiagnosticLogStore,
) {
    fun activate(
        localEntries: List<WorldBookEntry>,
        selectedCard: CharacterCard?,
        scanText: String,
        recentTexts: List<String>,
        state: WorldBookActivationState,
        stateKey: String,
        onBudgetExceeded: (Int) -> Unit,
    ) = LocalWorldBookService().activateEntriesWithReport(
        entries = localEntries + embeddedEntries(selectedCard),
        scanText = scanText,
        recentTexts = recentTexts,
        maxBudget = LocalWorldBookService.DEFAULT_MAX_BUDGET,
        state = state,
    ).also { report ->
        diagnosticStore.info(
            "worldbook.activated",
            "count=${report.activatedEntries.size}; " +
                "chars=${report.activatedEntries.sumOf { it.entry.content.length }}; " +
                "dropped=${report.droppedByBudget}; source=${localEntries.size}",
        )
        if (report.droppedByBudget > 0) {
            diagnosticStore.warn(
                "worldbook.budget_dropped",
                "count=${report.droppedByBudget}; budget=${LocalWorldBookService.DEFAULT_MAX_BUDGET}",
            )
            onBudgetExceeded(report.droppedByBudget)
        }
        runCatching {
            val stateJson = org.json.JSONObject()
                .put("turn", state.turn)
                .put("lastActivatedTurn", org.json.JSONObject(state.lastActivatedTurn.mapValues { it.value as Any }))
                .put("stickyUntilTurn", org.json.JSONObject(state.stickyUntilTurn.mapValues { it.value as Any }))
                .put("delayedUntilTurn", org.json.JSONObject(state.delayedUntilTurn.mapValues { it.value as Any }))
            settings.edit().putString(stateKey, stateJson.toString()).apply()
        }.onFailure { error ->
            diagnosticStore.warn(
                "worldbook.state_persist_failed",
                "reason=${error::class.java.simpleName}",
            )
        }
    }

    private fun embeddedEntries(selectedCard: CharacterCard?): List<WorldBookEntry> =
        selectedCard?.let(CharacterBookEntryCache::entries).orEmpty()
}

/** Parses a character's embedded worldbook once per card content. */
private object CharacterBookEntryCache {
    private var cachedName: String? = null
    private var cachedRaw: String? = null
    private var cachedEntries: List<WorldBookEntry> = emptyList()

    @Synchronized
    fun entries(card: CharacterCard): List<WorldBookEntry> {
        val raw = card.characterBookJson
        if (cachedName == card.name && cachedRaw == raw) return cachedEntries
        val parsed = raw?.let { json ->
            runCatching {
                WorldBookParser.parse(json, "角色卡：${card.name}")?.entries.orEmpty().map { entry ->
                    entry.copy(
                        id = "character-book:${card.name}:${entry.id}",
                        bookId = "character-book:${card.name}:${entry.bookId}",
                    )
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        cachedName = card.name
        cachedRaw = raw
        cachedEntries = parsed
        return parsed
    }
}
