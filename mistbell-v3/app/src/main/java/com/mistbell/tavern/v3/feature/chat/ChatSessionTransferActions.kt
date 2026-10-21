package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatSessionTransferActions(
    enabled: Boolean,
    onImport: () -> Unit,
    onExportJson: () -> Unit,
    onExportJsonl: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            enabled = enabled,
            onClick = onImport,
            modifier = Modifier.weight(1f),
        ) { Text("导入 JSON/JSONL") }
        Button(
            enabled = enabled,
            onClick = onExportJson,
            modifier = Modifier.weight(1f),
        ) { Text("导出 JSON") }
        Button(
            enabled = enabled,
            onClick = onExportJsonl,
            modifier = Modifier.weight(1f),
        ) { Text("导出 JSONL") }
    }
}
