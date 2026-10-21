package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun ChatCharacterSelector(
    selectedCharacterName: String?,
    characterOptions: List<Pair<String, String>>,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onSelectDefault: () -> Unit,
    onSelectCharacter: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("当前角色：${selectedCharacterName ?: "默认助手"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (selectedCharacterName == null) "尚未绑定角色卡；本轮使用默认系统提示。" else "角色卡会注入描述、性格、场景、开场白、示例对话及系统指令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { onMenuExpandedChange(true) }) { Text("选择角色") }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text("默认助手") },
                onClick = onSelectDefault,
            )
            characterOptions.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelectCharacter(id) },
                )
            }
        }
    }
}
