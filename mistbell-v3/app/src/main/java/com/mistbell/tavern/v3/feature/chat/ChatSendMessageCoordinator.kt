package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.character.CharacterCard
import com.mistbell.tavern.v3.feature.worldbook.WorldBookActivationState
import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry

/**
 * Prepares the existing chat history and prompt inputs for one outgoing user turn.
 *
 * This extracts prompt/history/worldbook preparation from ChatScreen without changing
 * the order of operations or the existing activation and prompt assembly policies.
 */
internal data class ChatSendMessagePreparation(
    val messages: List<PromptMessage>,
)

internal class ChatSendMessageCoordinator(
    private val settings: SharedPreferences,
    private val diagnosticStore: DiagnosticLogStore,
) {
    @Suppress("LongParameterList")
    fun prepare(
        history: List<ChatLine>,
        userText: String,
        selectedCard: CharacterCard?,
        characterDefinition: String?,
        localEntries: List<WorldBookEntry>,
        worldbookState: WorldBookActivationState,
        worldbookStateKey: String,
        onBudgetExceeded: (Int) -> Unit,
        historyIsComplete: Boolean = false,
        postHistoryInstructions: String? = null,
    ): ChatSendMessagePreparation {
        val completedLines = if (historyIsComplete) history else history.dropLast(2)
        val completeHistory = completedLines.mapNotNull { line ->
            if (line.content.isBlank()) {
                null
            } else {
                PromptMessage(
                    if (line.role == "user") PromptRole.USER else PromptRole.ASSISTANT,
                    line.content,
                )
            }
        }
        val historyStarted = System.nanoTime()
        val contextWindow = ChatPromptHistoryCoordinator(settings).build(completeHistory)
        val historyMs = (System.nanoTime() - historyStarted) / NANOS_PER_MILLI

        // Activate entries from the asynchronously loaded local library against
        // the current turn plus a bounded tail of recent conversation.
        val activationStarted = System.nanoTime()
        val activationReport = ChatWorldBookActivationCoordinator(
            settings = settings,
            diagnosticStore = diagnosticStore,
        ).activate(
            localEntries = localEntries,
            selectedCard = selectedCard,
            scanText = userText,
            recentTexts = completeHistory.takeLast(6).map { it.content },
            state = worldbookState,
            stateKey = worldbookStateKey,
            onBudgetExceeded = onBudgetExceeded,
        )
        val activationMs = (System.nanoTime() - activationStarted) / NANOS_PER_MILLI
        val assemblyStarted = System.nanoTime()
        val messages = ChatPromptAssemblyCoordinator().assemble(
            characterDefinition = characterDefinition,
            contextWindow = contextWindow,
            activatedWorldbook = activationReport.activatedEntries.map { it.entry },
            latestUserMessage = userText,
            postHistoryInstructions = postHistoryInstructions,
        )
        val assemblyMs = (System.nanoTime() - assemblyStarted) / NANOS_PER_MILLI
        diagnosticStore.info(
            "chat.prompt.stages",
            "historyMs=$historyMs; activationMs=$activationMs; assemblyMs=$assemblyMs",
        )
        return ChatSendMessagePreparation(messages = messages)
    }
}

private const val NANOS_PER_MILLI = 1_000_000
