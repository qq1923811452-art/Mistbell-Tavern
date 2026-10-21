package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun ChatGenerationLimitCard(
    value: String,
    usageSummary: String,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.dp(8)),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("最大生成 Token（max_tokens，1–200000）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(usageSummary, style = MaterialTheme.typography.bodySmall)
    }
}
