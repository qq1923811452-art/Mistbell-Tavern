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
    fun create(
        providersForRequest: ProviderProfileSet,
        conversationId: String,
        assistantMessageId: String,
        isCurrentRequest: () -> Boolean,
    ): ChatStreamObserver =
        object : ChatStreamObserver {
            @Volatile private var lastFinishReason: ChatFinishReason? = null
            @Volatile private var reasoningChars: Int = 0

            private fun updateAssistant(transform: (ChatLine) -> ChatLine) {
                mainHandler.post {
                    if (!isCurrentRequest() || conversationId.isBlank()) return@post
                    val index = history.indexOfFirst { it.id == assistantMessageId }
                    if (index >= 0) history[index] = transform(history[index])
                }
            }

            override fun onDelta(text: String) {
                updateAssistant { line -> line.copy(content = line.content + text) }
            }

            override fun onReasoningDelta(text: String) {
                reasoningChars += text.length
            }

            override fun onUsage(usage: ChatTokenUsage) {
                mainHandler.post {
                    if (!isCurrentRequest()) return@post
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
                        "cachedPromptTokens=${usage.cachedPromptTokens ?: "unknown"}; " +
                        "reasoningTokens=${usage.reasoningTokens ?: "unknown"}",
                )
            }

            override fun onFinish(reason: ChatFinishReason) {
                lastFinishReason = reason
                diagnosticStore.info("chat.stream.finished", "reason=${reason.name}")
                if (reasoningChars > 0) {
                    diagnosticStore.info("chat.reasoning", "reasoningChars=$reasoningChars")
                }
            }

            override fun onComplete() {
                mainHandler.post {
                    if (!isCurrentRequest()) return@post
                    setBusy(false)
                    val base = when (lastFinishReason) {
                        ChatFinishReason.LENGTH -> "回复达到输出上限，已被截断。"
                        ChatFinishReason.EOF -> "回复提前结束：未收到完成标记。"
                        ChatFinishReason.OTHER -> "回复已结束：服务商返回了未知结束原因。"
                        else -> "回复完成。"
                    }
                    setStatus(if (memoryStatusNotice().isBlank()) {
                        base
                    } else {
                        "$base${memoryStatusNotice()}"
                    })
                    val index = history.indexOfFirst { it.id == assistantMessageId }
                    if (index >= 0) {
                        history[index] = history[index].copy(
                            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
                        )
                    }
                    persistHistory()
                    scheduleMemoryExtraction(providersForRequest)
                }
            }

            override fun onFailure(failure: ChatStreamFailure) {
                mainHandler.post {
                    if (!isCurrentRequest()) return@post
                    setBusy(false)
                    setStatus(failure.safeMessage)
                    val index = history.indexOfFirst { it.id == assistantMessageId }
                    if (index >= 0) {
                        history[index] = history[index].copy(
                            content = history[index].content.ifBlank { failure.safeMessage },
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
