package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Renders the existing conversation history without changing message rendering rules.
 */
@Composable
internal fun ColumnScope.ChatHistoryMessageList(
    history: List<ChatLine>,
    busy: Boolean,
    listState: LazyListState,
    onSelectVariant: (messageId: String, index: Int) -> Unit = { _, _ -> },
) {
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(history) { _, line ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        when {
                            line.role == "user" -> "你"
                            line.speakerName.isNullOrBlank() -> "Mistbell"
                            else -> line.speakerName
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        line.activeContent.ifBlank {
                            when {
                                busy && line.role == "assistant" -> "正在生成……"
                                line.deliveryState == PersistedChatMessage.DeliveryState.PENDING ->
                                    "上次回复未完成，可重新发送。"
                                line.deliveryState == PersistedChatMessage.DeliveryState.FAILED &&
                                    line.role == "assistant" ->
                                    "上次回复未完成，未生成完整回答。"
                                else -> ""
                            }
                        },
                    )
                    if (line.variants.size > 1) {
                        SwipeControls(
                            index = line.activeVariant,
                            total = line.variants.size,
                            onPrevious = { onSelectVariant(line.id, line.activeVariant - 1) },
                            onNext = { onSelectVariant(line.id, line.activeVariant + 1) },
                        )
                    }
                }
            }
        }
    }
}
