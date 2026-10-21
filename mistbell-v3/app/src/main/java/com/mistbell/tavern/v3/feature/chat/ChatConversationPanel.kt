package com.mistbell.tavern.v3.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListState

@Composable
internal fun ChatConversationPanel(
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
