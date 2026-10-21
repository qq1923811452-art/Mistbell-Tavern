package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

@Composable
internal fun ColumnScope.ChatConversationPanel(
    status: String,
    messages: List<ChatLine>,
    generating: Boolean,
    state: LazyListState,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    inputEnabled: Boolean,
) {
    ChatStatusLine(message = status)
    ChatHistoryMessageList(
        history = messages,
        busy = generating,
        listState = state,
    )
    ChatMessageComposerInput(
        value = inputText,
        onValueChange = onInputTextChange,
        enabled = inputEnabled,
    )
}
