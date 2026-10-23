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
        historyIsComplete: Boolean = false,
        groupContext: GroupChatPromptContext? = null,
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
            historyIsComplete = historyIsComplete,
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
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        val promptChars = finalMessages.sumOf { it.content.length }
        diagnosticStore.info(
            "chat.prompt.assembled",
            "messages=${finalMessages.size}; promptChars=$promptChars; elapsedMs=$elapsedMillis",
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
        var assistantIndex = 0
        val block = GroupPromptAssembler.systemBlock(context.members, context.speakerName)
        return messages.map { message ->
            when (message.role) {
                PromptRole.SYSTEM -> message.copy(content = message.content + "\n\n" + block)
                PromptRole.ASSISTANT -> {
                    val name = assistantNames.getOrNull(assistantIndex)
                    assistantIndex += 1
                    message.copy(
                        content = GroupPromptAssembler.labelAssistantContent(name, message.content),
                    )
                }
                else -> message
            }
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000
