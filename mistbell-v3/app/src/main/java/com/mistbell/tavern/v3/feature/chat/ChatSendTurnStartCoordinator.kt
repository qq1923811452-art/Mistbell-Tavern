package com.mistbell.tavern.v3.feature.chat

/** Applies the existing side effects that begin a chat turn, in their original order. */
internal class ChatSendTurnStartCoordinator(
    private val history: MutableList<ChatLine>,
    private val persistHistory: () -> Unit,
    private val clearDraft: () -> Unit,
    private val setBusy: (Boolean) -> Unit,
    private val setMemoryStatusNotice: (String) -> Unit,
    private val setStatus: (String) -> Unit,
) {
    fun start(
        openingMessage: String,
        userText: String,
        charName: String? = null,
    ) {
        ChatTurnHistoryCoordinator().appendPendingTurn(history, openingMessage, userText, charName)
        persistHistory()
        clearDraft()
        setBusy(true)
        setMemoryStatusNotice("")
        setStatus("正在连接聊天 API……")
    }
}
