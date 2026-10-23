package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard

/** Session-scoped settings: the character and the transcript, not the global API. */
@Suppress("FunctionNaming", "LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSessionSettingsSheet(
    characters: List<StoredCharacterCard>,
    selectedCharacterId: String,
    messageCount: Int,
    onSelectCharacter: (String) -> Unit,
    onClearSession: () -> Unit,
    onOpenGlobalSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("会话设置", style = MaterialTheme.typography.titleLarge)
            Text(
                "本会话 $messageCount 条消息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "当前角色",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            characters.forEach { character ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCharacter(character.id) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        character.card.name + if (character.id == selectedCharacterId) "  ✓" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                TextButton(onClick = onClearSession) { Text("清空本会话") }
                TextButton(onClick = onOpenGlobalSettings) { Text("API 与生成设置") }
            }
        }
    }
}
