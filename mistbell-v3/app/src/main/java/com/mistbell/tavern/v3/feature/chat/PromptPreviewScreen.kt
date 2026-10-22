package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Read-only view of the prompt that was sent for the most recent turn. */
@Suppress("FunctionNaming")
@Composable
internal fun PromptPreviewScreen(
    preview: PromptPreviewBuilder.Preview,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提示词预览") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ 返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "共 ${preview.sections.size} 条，${preview.totalChars} 字符",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(preview.sections) { section ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(section.label, style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${section.chars} 字符",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(section.preview, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
