package com.mistbell.tavern.v3.feature.chat

import android.os.Handler
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.model.ProviderProfileSet

/**
 * Owns the existing stream callbacks that update visible message delivery state and usage.
 */
internal class ChatStreamObserverCoordinator(
    private val mainHandler: Handler,
    private val history: MutableList<ChatLine>,
    private val diagnosticStore: DiagnosticLogStore,
    private val setBusy: (Boolean) -> Unit,
    private val setStatus: (String) -> Unit,
    private val memoryStatusNotice: () -> String,
    private val setUsageSummary: (String) -> Unit,
    private val persistHistory: () -> Unit,
    private val scheduleMemoryExtraction: (ProviderProfileSet) -> Unit,
) {
    fun create(providersForRequest: ProviderProfileSet): ChatStreamObserver =
        object : ChatStreamObserver {
            override fun onDelta(text: String) {
                mainHandler.post {
                    val last = history.lastOrNull()
                    if (last?.role == "assistant") {
                        history[history.lastIndex] = last.copy(content = last.content + text)
                    }
                }
            }

            override fun onUsage(usage: ChatTokenUsage) {
                mainHandler.post {
                    val cached = usage.cachedPromptTokens
                    val cacheRatio = if (cached != null && usage.promptTokens > 0) {
                        "（${cached * PERCENT / usage.promptTokens}%）"
                    } else {
                        ""
                    }
                    setUsageSummary(
                        "Token 用量：输入 ${usage.promptTokens} · 缓存 ${cached?.toString() ?: "未知"}$cacheRatio" +
                            " · 输出 ${usage.completionTokens} · 合计 ${usage.totalTokens}",
                    )
                }
                diagnosticStore.info(
                    "chat.usage",
                    "promptTokens=${usage.promptTokens}; completionTokens=${usage.completionTokens}; " +
                        "totalTokens=${usage.totalTokens}; " +
                        "cachedPromptTokens=${usage.cachedPromptTokens ?: "unknown"}",
                )
            }

            override fun onComplete() {
                mainHandler.post {
                    setBusy(false)
                    setStatus(if (memoryStatusNotice().isBlank()) {
                        "回复完成。"
                    } else {
                        "回复完成。${memoryStatusNotice()}"
                    })
                    val last = history.lastOrNull()
                    if (last?.role == "assistant") {
                        history[history.lastIndex] = last.copy(
                            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
                        )
                    }
                    persistHistory()
                    scheduleMemoryExtraction(providersForRequest)
                }
            }

            override fun onFailure(failure: ChatStreamFailure) {
                mainHandler.post {
                    setBusy(false)
                    setStatus(failure.safeMessage)
                    val last = history.lastOrNull()
                    if (last?.role == "assistant") {
                        history[history.lastIndex] = last.copy(
                            content = last.content.ifBlank { failure.safeMessage },
                            deliveryState = PersistedChatMessage.DeliveryState.FAILED,
                        )
                    }
                    persistHistory()
                }
            }
        }

    private companion object {
        const val PERCENT = 100
    }
}
