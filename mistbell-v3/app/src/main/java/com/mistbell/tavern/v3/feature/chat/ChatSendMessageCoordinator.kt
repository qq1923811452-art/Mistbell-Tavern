package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.character.CharacterCard
import com.mistbell.tavern.v3.feature.memory.stripThinkingBlocks
import com.mistbell.tavern.v3.feature.worldbook.ActivatedWorldBookEntry
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
    private val historyPlanner: StableConversationWindowPlanner,
    private val historyWindowCache: WindowStateCache? = null,
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
        historyScopeId: String,
        invalidateHistoryWindow: Boolean = false,
    ): ChatSendMessagePreparation {
        val charName = selectedCard?.name
        val completedLines = completedLinesWithoutThinkingBlocks(history, historyIsComplete)
        val completeHistory =
            completedLines.mapNotNull { line ->
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
        val decision =
            ChatPromptHistoryCoordinator(settings, historyPlanner, historyWindowCache).build(
                scopeId = historyScopeId,
                history = completedLines,
                invalidate = invalidateHistoryWindow,
            )
        val contextWindow = decision.window
        diagnosticStore.info("chat.window.decision", decision.diagnosticText())
        val historyMs = (System.nanoTime() - historyStarted) / NANOS_PER_MILLI

        // Activate entries from the asynchronously loaded local library against
        // the current turn plus a bounded tail of recent conversation.
        val activationStarted = System.nanoTime()
        val activationReport =
            ChatWorldBookActivationCoordinator(
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
        val messages =
            ChatPromptAssemblyCoordinator().assemble(
                characterDefinition = characterDefinition?.let { MacroEngine.resolve(it, charName) },
                contextWindow = contextWindow,
                activatedWorldbook =
                    resolveWorldbookContent(activationReport.activatedEntries, charName),
                latestUserMessage = userText,
                postHistoryInstructions = postHistoryInstructions?.let { MacroEngine.resolve(it, charName) },
                cardSystemInstructions = selectedCard?.systemPrompt,
            )
        val assemblyMs = (System.nanoTime() - assemblyStarted) / NANOS_PER_MILLI
        diagnosticStore.info(
            "chat.prompt.stages",
            "historyMs=$historyMs; activationMs=$activationMs; assemblyMs=$assemblyMs",
        )
        return ChatSendMessagePreparation(messages = messages)
    }

    /** Closed reasoning blocks must not consume the prompt window. */
    private fun completedLinesWithoutThinkingBlocks(
        history: List<ChatLine>,
        historyIsComplete: Boolean,
    ): List<ChatLine> =
        (if (historyIsComplete) history else history.dropLast(2)).map { line ->
            line.copy(content = stripThinkingBlocks(line.content))
        }

    private fun resolveWorldbookContent(
        entries: List<ActivatedWorldBookEntry>,
        charName: String?,
    ): List<WorldBookEntry> =
        entries.map { activated ->
            activated.entry.copy(content = MacroEngine.resolve(activated.entry.content, charName))
        }
}

private const val NANOS_PER_MILLI = 1_000_000
