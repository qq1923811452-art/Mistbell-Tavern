package com.mistbell.tavern.v3.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun ChatStatusLine(
    message: String,
) {
    Text(message, style = MaterialTheme.typography.bodySmall)
}
