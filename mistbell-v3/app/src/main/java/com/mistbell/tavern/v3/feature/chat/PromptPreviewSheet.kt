@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "LongParameterList")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.ui.tavern.TavernGlassCard
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import com.mistbell.tavern.v3.ui.tavern.TavernSheetHeader

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
    val subtitle = "${messages.size} 条消息 · 约 $estimatedTokens tokens · 共 $totalChars 字符"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TavernPalette.Ink,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
            TavernSheetHeader(title = "本次提示词", subtitle = subtitle)
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier.padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TavernPill(label = "按消息", onClick = { showFullText = false }, filled = !showFullText)
                    TavernPill(label = "完整文本", onClick = { showFullText = true }, filled = showFullText)
                    TavernPill(
                        label = "复制全文",
                        onClick = { clipboard.setText(AnnotatedString(fullText)) },
                        glyph = TavernGlyphName.Prompt,
                    )
                }
                if (usageSummary.isNotBlank()) {
                    Text(
                        text = usageSummary,
                        color = TavernPalette.TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (showFullText) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item {
                            PromptBodyCard(text = fullText)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(messages) { index, message ->
                            MessageCard(index = index, message = message)
                        }
                    }
                }
            }
        }
    }
}

/** Full prompt as one glass panel; prose stays sans-serif so Chinese stays readable. */
@Suppress("FunctionNaming")
@Composable
private fun PromptBodyCard(text: String) {
    TavernGlassCard(Modifier.fillMaxWidth(), shape = TavernShape.CardSmall) {
        Text(
            text = text,
            color = TavernPalette.TextPrimary.copy(alpha = BODY_ALPHA),
            fontSize = 12.5.sp,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
        )
    }
}

/** One prompt message: role caption plus token estimate, then the raw text. */
@Suppress("FunctionNaming")
@Composable
private fun MessageCard(
    index: Int,
    message: PromptMessage,
) {
    TavernGlassCard(Modifier.fillMaxWidth(), shape = TavernShape.CardSmall) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${index + 1} ${message.role.name}",
                    color = TavernPalette.TextTertiary,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "~${message.content.length / CHARS_PER_TOKEN} tok",
                    color = TavernPalette.TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = message.content,
                color = TavernPalette.TextPrimary.copy(alpha = BODY_ALPHA),
                fontSize = 12.5.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Rough token estimate for mixed Chinese/English text. */
private const val CHARS_PER_TOKEN = 2

/** Prompt prose sits just under full strength so the glass edge stays visible. */
private const val BODY_ALPHA = 0.92f
