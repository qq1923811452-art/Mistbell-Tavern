package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import android.os.Handler
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard
import com.mistbell.tavern.v3.feature.worldbook.WorldBookActivationState
import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry

/** Speaker information needed to label a group conversation prompt. */
internal data class GroupChatPromptContext(
    val members: List<GroupPromptAssembler.Member>,
    val speakerName: String,
)

/** Resolves the selected character and assembles prompt messages for one outgoing turn. */
internal class ChatTurnPromptPreparationCoordinator(
    private val settings: SharedPreferences,
    private val diagnosticStore: DiagnosticLogStore,
    private val mainHandler: Handler,
    private val setStatus: (String) -> Unit,
    private val historyPlanner: StableConversationWindowPlanner,
    private val historyWindowCache: WindowStateCache? = null,
) {
    @Suppress("LongParameterList")
    fun prepare(
        history: List<ChatLine>,
        userText: String,
        characterCards: List<StoredCharacterCard>,
        selectedCharacterId: String,
        localWorldbookEntries: List<WorldBookEntry>,
        worldbookState: WorldBookActivationState,
        worldbookStateKey: String,
        historyScopeId: String,
        historyIsComplete: Boolean = false,
        groupContext: GroupChatPromptContext? = null,
        invalidateHistoryWindow: Boolean = false,
    ): List<PromptMessage> {
        val startedNanos = System.nanoTime()
        val selectedCard = characterCards.firstOrNull { it.id == selectedCharacterId }?.card
        val formatStarted = System.nanoTime()
        val characterDefinition = selectedCard?.let(CharacterDefinitionCache::format)
        val formatMs = (System.nanoTime() - formatStarted) / NANOS_PER_MILLI
        val prepareStarted = System.nanoTime()
        val messages = ChatSendMessageCoordinator(
            settings = settings,
            diagnosticStore = diagnosticStore,
            historyPlanner = historyPlanner,
            historyWindowCache = historyWindowCache,
        ).prepare(
            history = history,
            userText = userText,
            selectedCard = selectedCard,
            characterDefinition = characterDefinition,
            localEntries = localWorldbookEntries,
            worldbookState = worldbookState,
            worldbookStateKey = worldbookStateKey,
            historyIsComplete = historyIsComplete,
            historyScopeId = historyScopeId,
            invalidateHistoryWindow = invalidateHistoryWindow,
            postHistoryInstructions = selectedCard?.postHistoryInstructions,
            onBudgetExceeded = { droppedCount ->
                mainHandler.post {
                    setStatus("世界书预算超限：本轮有 " + droppedCount + " 个条目未注入")
                }
            },
        ).messages
        val finalMessages = if (groupContext == null) {
            messages
        } else {
            labelGroupMessages(messages, history, historyIsComplete, groupContext)
        }
        val prepareMs = (System.nanoTime() - prepareStarted) / NANOS_PER_MILLI
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        val promptChars = finalMessages.sumOf { it.content.length }
        diagnosticStore.info(
            "chat.prompt.assembled",
            "messages=${finalMessages.size}; promptChars=$promptChars; elapsedMs=$elapsedMillis; " +
                "formatMs=$formatMs; prepareMs=$prepareMs; " +
                PromptCacheDiagnostics.describe(finalMessages, historyPlanner.exportState()?.pinnedMessageCount ?: 0),
        )
        return finalMessages
    }

    private fun labelGroupMessages(
        messages: List<PromptMessage>,
        history: List<ChatLine>,
        historyIsComplete: Boolean,
        context: GroupChatPromptContext,
    ): List<PromptMessage> {
        val completed = if (historyIsComplete) history else history.dropLast(2)
        val assistantNames = completed
            .filter { it.role == "assistant" && it.content.isNotBlank() }
            .map { it.speakerName }
        val block = GroupPromptAssembler.systemBlock(context.members, context.speakerName)
        val pinnedMessages = historyPlanner.exportState()?.pinnedMessageCount ?: 0
        val pinnedAssistants = completed.filter { it.content.isNotBlank() }.take(pinnedMessages)
            .count { it.role == "assistant" }
        val labelled = GroupPromptAssembler.labelHistoryAssistantMessages(messages, assistantNames, pinnedAssistants)
        return labelled.map { message ->
            if (message.role == PromptRole.SYSTEM) {
                message.copy(content = message.content + "\n\n" + block)
            } else {
                message
            }
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000

/** Formats a card once; the formatted definition is stable between turns. */
private object CharacterDefinitionCache {
    private var cachedCard: com.mistbell.tavern.v3.feature.character.CharacterCard? = null
    private var cachedDefinition: String = ""

    @Synchronized
    fun format(card: com.mistbell.tavern.v3.feature.character.CharacterCard): String {
        if (cachedCard == card) return cachedDefinition
        val formatted = ChatCharacterPromptFormatter.format(card)
        cachedCard = card
        cachedDefinition = formatted
        return formatted
    }
}
