package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatEmbeddingDimensionSelector(
    selectedDimensions: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectDimensions: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onExpandedChange(true) }) {
            Text("向量维度：${selectedDimensions} 维")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            CHAT_EMBEDDING_DIMENSION_OPTIONS.forEach { dimensions ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (dimensions <= 768) "$dimensions 维"
                            else "$dimensions 维（当前模型不支持）",
                        )
                    },
                    onClick = { onSelectDimensions(dimensions) },
                )
            }
        }
    }
}
