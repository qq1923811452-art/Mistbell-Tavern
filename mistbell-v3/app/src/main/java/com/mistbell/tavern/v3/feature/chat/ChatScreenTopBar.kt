package com.mistbell.tavern.v3.feature.chat

import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreenTopBar(
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text("Mistbell Tavern · 聊天测试") },
        navigationIcon = {
            Button(onClick = onBack) { Text("返回首页") }
        },
    )
}
