package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import android.os.Handler
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard
import com.mistbell.tavern.v3.feature.worldbook.WorldBookActivationState
import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry

/** Resolves the selected character and assembles prompt messages for one outgoing turn. */
internal class ChatTurnPromptPreparationCoordinator(
    private val settings: SharedPreferences,
    private val diagnosticStore: DiagnosticLogStore,
    private val mainHandler: Handler,
    private val setStatus: (String) -> Unit,
) {
    fun prepare(
        history: List<ChatLine>,
        userText: String,
        characterCards: List<StoredCharacterCard>,
        selectedCharacterId: String,
        localWorldbookEntries: List<WorldBookEntry>,
        worldbookState: WorldBookActivationState,
        worldbookStateKey: String,
    ): List<PromptMessage> {
        val startedNanos = System.nanoTime()
        val selectedCard = characterCards.firstOrNull { it.id == selectedCharacterId }?.card
        val messages = ChatSendMessageCoordinator(
            settings = settings,
            diagnosticStore = diagnosticStore,
        ).prepare(
            history = history,
            userText = userText,
            selectedCard = selectedCard,
            characterDefinition = selectedCard?.let(ChatCharacterPromptFormatter::format),
            localEntries = localWorldbookEntries,
            worldbookState = worldbookState,
            worldbookStateKey = worldbookStateKey,
            onBudgetExceeded = { droppedCount ->
                mainHandler.post {
                    setStatus("世界书预算超限：本轮有 " + droppedCount + " 个条目未注入")
                }
            },
        ).messages
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        val promptChars = messages.sumOf { it.content.length }
        diagnosticStore.info(
            "chat.prompt.assembled",
            "messages=${messages.size}; promptChars=$promptChars; elapsedMs=$elapsedMillis",
        )
        return messages
    }
}

private const val NANOS_PER_MILLI = 1_000_000
