package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import java.util.UUID

internal class ChatSessionResetCoordinator(
    private val settings: SharedPreferences,
    private val clearHistory: () -> Unit,
    private val setHistoryLoadFailed: (Boolean) -> Unit,
    private val setConversationId: (String) -> Unit,
    private val setStatus: (String) -> Unit,
) {
    fun reset(
        historyLoadFailed: Boolean,
        previousMemoryScopeId: String,
    ) {
        val replacingUnreadableHistory = historyLoadFailed
        clearHistory()
        setHistoryLoadFailed(false)
        val newConversationId = UUID.randomUUID().toString()
        setConversationId(newConversationId)
        ChatScopeSwitchCoordinator(settings).persist(previousMemoryScopeId, newConversationId)
        // The new scope gets its own history file. Do not overwrite the
        // previous scope's persisted conversation with an empty list.
        setStatus(
            if (replacingUnreadableHistory) {
                "已放弃无法读取的旧聊天记录并新建会话；新会话使用独立记忆索引，旧会话记忆文件保留但不会自动注入。"
            } else {
                "会话已清空并切换到独立记忆范围；旧会话记忆文件仍保留，但不会注入新会话。"
            },
        )
    }
}
