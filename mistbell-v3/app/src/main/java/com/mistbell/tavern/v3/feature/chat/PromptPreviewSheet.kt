package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** Bottom sheet that shows the exact prompt of the latest turn. */
@Suppress("FunctionNaming", "LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptPreviewSheet(
    messages: List<PromptMessage>,
    usageSummary: String,
    onDismiss: () -> Unit,
) {
    if (messages.isEmpty()) return
    var showFullText by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val totalChars = messages.sumOf { it.content.length }
    val estimatedTokens = totalChars / CHARS_PER_TOKEN
    val fullText = messages.joinToString("\n\n") { "[${it.role}] ${it.content}" }
    ModalBottomSheet(onDismissRequest = onDismiss, showDragHandle = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("本次提示词", style = MaterialTheme.typography.titleLarge)
            Text(
                "${messages.size} 条消息 · 约 $estimatedTokens tokens · 共 $totalChars 字符",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usageSummary.isNotBlank()) {
                Text(
                    usageSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showFullText, onClick = { showFullText = false }, label = { Text("按消息") })
                FilterChip(selected = showFullText, onClick = { showFullText = true }, label = { Text("完整文本") })
                TextButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("复制全文") }
            }
            if (showFullText) {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Text(fullText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(messages) { index, message ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    "#${index + 1} ${message.role.name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "~${message.content.length / CHARS_PER_TOKEN} tok",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(message.content, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Rough token estimate for mixed Chinese/English text. */
private const val CHARS_PER_TOKEN = 2
