package com.mistbell.tavern.v3.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore

/** Runs memory-aware streaming and preserves the existing ordinary-chat fallback behavior. */
@Suppress("LongParameterList")
internal class ChatSendStreamFallbackCoordinator(
    private val coroutineScope: CoroutineScope,
    private val diagnosticStore: DiagnosticLogStore,
    private val history: MutableList<ChatLine>,
    private val setBusy: (Boolean) -> Unit,
    private val setStatus: (String) -> Unit,
    private val persistHistory: () -> Unit,
    private val isCurrentRequest: () -> Boolean,
) {
    fun launch(
        memoryStream: suspend () -> Unit,
        ordinaryStream: suspend () -> Unit,
    ) {
        coroutineScope.launch {
            if (!isCurrentRequest()) return@launch
            try {
                memoryStream()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentRequest()) return@launch
                diagnosticStore.warn(
                    "chat.embedding.fallback",
                    "reason=${error::class.java.simpleName}",
                )
                setStatus(
                    "本地记忆不可用，已尝试回退普通聊天：" +
                        (error.message?.take(120) ?: error::class.java.simpleName),
                )
                if (!isCurrentRequest()) return@launch
                try {
                    ordinaryStream()
                } catch (chatCancelled: CancellationException) {
                    throw chatCancelled
                } catch (chatError: Exception) {
                    if (!isCurrentRequest()) return@launch
                    setBusy(false)
                    val failureStatus = chatError.message?.take(180) ?: "聊天请求配置失败。"
                    setStatus(failureStatus)
                    val last = history.lastOrNull()
                    if (last?.role == "assistant") {
                        history[history.lastIndex] = last.copy(
                            content = last.content.ifBlank { failureStatus },
                            deliveryState = PersistedChatMessage.DeliveryState.FAILED,
                        )
                    }
                    persistHistory()
                }
            }
        }
    }
}
