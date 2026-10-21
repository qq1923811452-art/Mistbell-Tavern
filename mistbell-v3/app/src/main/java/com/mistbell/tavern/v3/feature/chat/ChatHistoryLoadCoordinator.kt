package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates the existing history-load and interrupted-reply recovery path.
 *
 * The screen callbacks preserve the original UI update order while storage work stays here.
 */
internal class ChatHistoryLoadCoordinator(
    private val historyStore: SqliteChatHistoryRepository,
    private val diagnosticStore: DiagnosticLogStore,
) {
    suspend fun load(
        onLoaded: (List<PersistedChatMessage>, Boolean) -> Unit,
        onRecoverySaveFailed: () -> Unit,
    ): ChatHistoryLoadResult {
        val loaded = withContext(Dispatchers.IO) { historyStore.load() }
        if (loaded !is ChatHistoryLoadResult.Loaded) return loaded

        var recoveredInterruptedReply = false
        val recoveredMessages = loaded.messages.map { message ->
            val interruptedAssistant = message.role == "assistant" &&
                message.deliveryState == PersistedChatMessage.DeliveryState.PENDING
            if (interruptedAssistant) recoveredInterruptedReply = true
            message.copy(
                deliveryState = if (interruptedAssistant) {
                    PersistedChatMessage.DeliveryState.FAILED
                } else {
                    message.deliveryState
                },
            )
        }
        onLoaded(recoveredMessages, recoveredInterruptedReply)

        if (recoveredInterruptedReply) {
            runCatching {
                withContext(Dispatchers.IO) { historyStore.save(recoveredMessages) }
            }.onFailure { error ->
                diagnosticStore.warn(
                    "chat.history.recovery_save_failed",
                    "reason=${error::class.java.simpleName}",
                )
                onRecoverySaveFailed()
            }
        }
        return loaded
    }
}
