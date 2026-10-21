package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth

@Composable
internal fun ChatEmbeddingDimensionsSettingsSection(
    selectedDimensions: Int,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onDimensionsSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.dp(8)),
    ) {
        Button(onClick = { onMenuExpandedChange(true) }) {
            Text("向量维度：$selectedDimensions 维")
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { onMenuExpandedChange(false) },
        ) {
            CHAT_EMBEDDING_DIMENSION_OPTIONS.forEach { dimensions ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (dimensions <= 768) "$dimensions 维"
                            else "$dimensions 维（当前模型不支持）",
                        )
                    },
                    onClick = { onDimensionsSelected(dimensions) },
                )
            }
        }
    }
}
