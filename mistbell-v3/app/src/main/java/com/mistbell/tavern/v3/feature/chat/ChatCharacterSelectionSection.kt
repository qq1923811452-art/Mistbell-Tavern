package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard

@Composable
internal fun ChatCharacterSelectionSection(
    selectedCharacterName: String?,
    hasSelectedCharacter: Boolean,
    characterCards: List<StoredCharacterCard>,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onCharacterSelected: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("聊天 API 配置", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("当前角色：${selectedCharacterName ?: "默认助手"}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (!hasSelectedCharacter) "尚未绑定角色卡；本轮使用默认系统提示。" else "角色卡会注入描述、性格、场景、开场白、示例对话及系统指令。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { onMenuExpandedChange(true) }) { Text("选择角色") }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("默认助手") },
                        onClick = { onCharacterSelected("") },
                    )
                    characterCards.forEach { stored ->
                        DropdownMenuItem(
                            text = { Text(stored.card.name) },
                            onClick = { onCharacterSelected(stored.id) },
                        )
                    }
                }
            }
        }
    }
}
