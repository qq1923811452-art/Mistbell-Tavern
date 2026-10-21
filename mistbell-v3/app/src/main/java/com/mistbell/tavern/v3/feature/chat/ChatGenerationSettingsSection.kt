package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

@Composable
internal fun ChatGenerationSettingsSection(
    settings: SharedPreferences,
    maxTokensText: String,
    usageSummary: String,
    onMaxTokensTextChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = maxTokensText,
        onValueChange = { value ->
            val sanitizedValue = value.filter(Char::isDigit).take(6)
            onMaxTokensTextChange(sanitizedValue)
            sanitizedValue.toIntOrNull()?.takeIf { it in 1..200_000 }?.let { limit ->
                settings.edit().putInt("chat_max_tokens", limit).apply()
            }
        },
        label = { Text("最大生成 Token（max_tokens，1–200000）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Text(usageSummary, style = MaterialTheme.typography.bodySmall)
}
