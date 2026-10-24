package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard

/** Creates a conversation only after the user picks its character and worldbook. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun CreateSessionDialog(
    characters: List<StoredCharacterCard>,
    worldbooks: List<Pair<String, String>>,
    initialRoleId: String,
    onDismiss: () -> Unit,
    onConfirm: (roleId: String, worldbookId: String) -> Unit,
) {
    var roleId by remember {
        mutableStateOf(
            initialRoleId.takeIf { id -> characters.any { it.id == id } }
                ?: characters.firstOrNull()?.id.orEmpty(),
        )
    }
    var worldbookId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建对话") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("选择角色", style = MaterialTheme.typography.titleSmall)
                if (characters.isEmpty()) {
                    Text("还没有角色卡，请先在设置中导入。")
                }
                characters.forEach { card ->
                    SelectableRow(
                        title = card.card.name,
                        selected = card.id == roleId,
                        onClick = { roleId = card.id },
                    )
                }
                Text(
                    "选择世界书",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SelectableRow(
                    title = "跟随全局",
                    selected = worldbookId.isEmpty(),
                    onClick = { worldbookId = "" },
                )
                worldbooks.forEach { (id, name) ->
                    SelectableRow(
                        title = name,
                        selected = worldbookId == id,
                        onClick = { worldbookId = id },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(roleId, worldbookId) },
                enabled = roleId.isNotBlank(),
            ) { Text("开始聊天") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun SelectableRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = if (selected) "✓ $title" else title,
            modifier = Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
