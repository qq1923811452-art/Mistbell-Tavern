package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class ConversationPaneState(
    val conversationId: String,
    val historyReady: Boolean,
    val history: List<ChatLine>,
    val busy: Boolean,
)

/** The list and all pending scroll work are discarded together on a scope change. */
@Composable
internal fun ColumnScope.chatConversationPane(
    pane: ConversationPaneState,
    onSelectVariant: (String, Int) -> Unit,
    onRegenerate: (String) -> Unit,
) {
    key(pane.conversationId) {
        val state = rememberLazyListState()
        val scroll = rememberConversationScroll(state, pane.historyReady, pane.history)
        Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(scroll.connection)) {
            Column(Modifier.fillMaxSize()) {
                ChatHistoryMessageList(
                    history = if (pane.historyReady) pane.history else emptyList(),
                    busy = pane.busy,
                    listState = state,
                    onSelectVariant = onSelectVariant,
                    onRegenerate = onRegenerate,
                )
            }
            if (pane.historyReady && scroll.showReturnToBottom) {
                TextButton(
                    onClick = scroll.onReturnToBottom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .testTag("conversation-bottom"),
                ) { Text("回到底部 ↓") }
            }
        }
    }
}

internal class ConversationScrollControls(
    val connection: NestedScrollConnection,
    val onReturnToBottom: () -> Unit,
) {
    var showReturnToBottom by mutableStateOf(false)
}

@Composable
internal fun rememberConversationScroll(
    state: LazyListState,
    historyReady: Boolean,
    history: List<ChatLine>,
): ConversationScrollControls {
    val ready by rememberUpdatedState(historyReady)
    val messages by rememberUpdatedState(history)
    val policy = remember { ConversationScrollPolicy() }
    val requests = remember { Channel<Unit>(Channel.CONFLATED) }
    val controls = remember(state) {
        ConversationScrollControls(
            connection = object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) policy.onUserScroll()
                    return Offset.Zero
                }
            },
            onReturnToBottom = {
                policy.returnToBottom()
                requests.trySend(Unit)
            },
        )
    }
    LaunchedEffect(state) {
        launch {
            snapshotFlow { ready to messages.lastOrNull { it.role == "user" }?.id }
                .collect { (loaded, userId) ->
                if (policy.onHistory(loaded, userId)) requests.trySend(Unit)
            }
        }
        launch {
            snapshotFlow { state.canScrollForward to state.isScrollInProgress }.collect { (forward, scrolling) ->
                if (ready && !scrolling) policy.onReachedBottom(forward)
                controls.showReturnToBottom = forward && !policy.followsBottom
            }
        }
        launch {
            // Size changes (including streamed text and IME resize), never the scroll offset itself.
            snapshotFlow {
                val layout = state.layoutInfo
                layout.viewportEndOffset to layout.visibleItemsInfo.lastOrNull()?.let { it.index to it.size }
            }.collect {
                if (ready && policy.followsBottom) requests.trySend(Unit)
            }
        }
        while (true) {
            requests.receive()
            // One worker, at most one snap per frame; no per-token animation cancellation.
            withFrameNanos { }
            if (ready && policy.followsBottom && !state.isScrollInProgress) {
                try {
                    state.snapToConversationBottom { policy.followsBottom }
                } catch (_: CancellationException) {
                    // UserInput can cancel a scroll mutation; keep the scope's observers alive.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }
    return controls
}

private suspend fun LazyListState.snapToConversationBottom(shouldFollow: () -> Boolean) {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) scrollToItem(lastIndex)
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    // The final bubble may be taller than the viewport: its index alone is not a bottom signal.
    val gap = last.offset.toLong() + last.size - layout.viewportEndOffset + layout.afterContentPadding
    if (gap > 0 && shouldFollow()) scrollBy(gap.toFloat())
}
