package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatContextBudgetCard(
    settings: SharedPreferences,
    recentTurnsText: String,
    onRecentTurnsChange: (String) -> Unit,
    summaryCharsText: String,
    onSummaryCharsChange: (String) -> Unit,
    historyCharsText: String,
    onHistoryCharsChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("上下文历史预算", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = recentTurnsText,
                onValueChange = onRecentTurnsChange,
                label = { Text("最近历史轮数（1–100）") },
                supportingText = { Text("按用户消息计轮；同一轮中的多条助手消息会一起保留。") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = summaryCharsText,
                onValueChange = onSummaryCharsChange,
                label = { Text("较早历史摘要上限（0–10000 字符）") },
                supportingText = { Text("0 表示不注入较早历史摘要；摘要为本地提取式短句，不会修改原始聊天记录。") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = historyCharsText,
                onValueChange = onHistoryCharsChange,
                label = { Text("最近历史字符预算（4000–100000）") },
                supportingText = { Text("默认 24000 字符。字符数不等于 Token 数；保留完整消息，不截断长回复，单条最新消息超限时仍会整条保留。") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "修改后从下一条消息生效。历史轮数、摘要和字符预算分别限制不同部分；字符数不等于模型 Token 数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
