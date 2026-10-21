package com.mistbell.tavern.v3.feature.chat

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun ChatSessionResetAction(
    enabled: Boolean,
    historyLoadFailed: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(if (historyLoadFailed) "放弃旧记录并新建会话" else "清空会话")
    }
}
