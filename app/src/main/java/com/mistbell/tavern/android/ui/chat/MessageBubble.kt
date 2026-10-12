package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.api.Usage
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.ui.theme.*
import java.time.format.DateTimeFormatter

// 时间格式化器提为常量：原先每条消息格式化时间都 ofPattern 重新编译一次，
// 消息多时开销可观；DateTimeFormatter 线程安全，可全局复用
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    characterName: String,
    characterColor: Color = AccentBlue,
    // 深浅色由调用方（应用内三态 darkModeSetting 算出的 isDark）透传给 MarkdownRenderer，
    // 避免 Markdown 组件读系统深色导致与应用内主题设置脱节
    dark: Boolean,
    isUser: Boolean,
    isLastInGroup: Boolean,
    isLastMessage: Boolean = false,
    onCopy: () -> Unit = {},
    onUndo: () -> Unit = {},
    onBacktrack: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onContinue: () -> Unit = {},
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    // 有思维链的消息进入聊天页时直接显示正文；仍可点击标题收起，避免用户误以为思维链没有生成。
    var thinkingExpanded by remember { mutableStateOf(true) }
    // 兼容早期版本：部分消息把 <think>...</think> 保存在 content，而没有单独写入 thinking 列。
    // 先从正文拆出再渲染，避免 Markdown 处理 HTML 标签后把思维链完全吞掉。
    val (inlineContent, inlineThinking) = remember(message.content, message.thinking) {
        splitThinkingForDisplay(message.content)
    }
    val displayedContent = if (inlineThinking != null) inlineContent else message.content
    val displayedThinking = message.thinking?.takeIf { it.isNotBlank() } ?: inlineThinking
    val bubbleShape = MaterialTheme.shapes.medium
    val cardBackgroundColor =
        if (isUser) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        }
    val cardBorderColor =
        if (isUser) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    val cardTextColor =
        if (isUser) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val cardShadowColor =
        if (isUser) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Black.copy(alpha = 0.04f)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLastInGroup) 20.dp else 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Sender label
        if (isLastInGroup) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.padding(
                        bottom = 4.dp,
                        start = if (isUser) 0.dp else 16.dp,
                        end = if (isUser) 16.dp else 0.dp,
                    ),
            ) {
                if (!isUser) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AccentGreen),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (isUser) "你" else characterName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 思考过程显示在对应回复上方，与消息保持同一侧对齐，但仍作为独立区域。
        if (!displayedThinking.isNullOrBlank()) {
            thinkingSection(
                thinking = displayedThinking.orEmpty(),
                expanded = thinkingExpanded,
                onExpandedChange = { thinkingExpanded = it },
                modifier =
                    Modifier.padding(
                        bottom = 4.dp,
                        start = if (isUser) 0.dp else 16.dp,
                        end = if (isUser) 16.dp else 0.dp,
                    ),
            )
        }
        // Message row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            // No avatar - it's now in the background
            // Bubble with long-press menu
            Box {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = if (isUser) 320.dp else 620.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showMenu = true },
                            )
                            .then(
                                Modifier
                                    .shadow(0.dp, bubbleShape, ambientColor = cardShadowColor)
                                    .background(cardBackgroundColor, bubbleShape)
                                    .border(1.dp, cardBorderColor, bubbleShape),
                            )
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    if (isUser) {
                        // 用户消息也使用 MarkdownRenderer 以支持语义高亮（引号、括号变色）
                        MarkdownRenderer(
                            content = displayedContent,
                            dark = dark,
                            highlightColor = MaterialTheme.colorScheme.onPrimary,
                            textColor = cardTextColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        MarkdownRenderer(
                            content = displayedContent,
                            // 透传应用内三态深浅色，而非组件内部读系统设置
                            dark = dark,
                            highlightColor = MaterialTheme.colorScheme.tertiary,
                            textColor = cardTextColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // Long-press context menu
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            showMenu = false
                            onCopy()
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                    )
                    if (!isUser && !isLastMessage) {
                        DropdownMenuItem(
                            text = { Text("回退到这里") },
                            onClick = {
                                showMenu = false
                                onBacktrack()
                            },
                            leadingIcon = { Icon(Icons.Default.Replay, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (!isUser) {
                        DropdownMenuItem(
                            text = { Text("重新生成") },
                            onClick = {
                                showMenu = false
                                onRegenerate()
                            },
                            leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (isLastMessage && !isUser) {
                        DropdownMenuItem(
                            text = { Text("继续") },
                            onClick = {
                                showMenu = false
                                onContinue()
                            },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (isLastMessage && isUser) {
                        DropdownMenuItem(
                            text = { Text("撤销") },
                            onClick = {
                                showMenu = false
                                onUndo()
                            },
                            leadingIcon = { Icon(Icons.Default.Undo, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    // Swipe controls for AI messages with multiple swipes
                    if (!isUser && message.swipes != null && message.swipes.size > 1) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("← 上一个 (${message.swipeIndex}/${message.swipes.size - 1})") },
                            onClick = {
                                showMenu = false
                                onSwipeLeft()
                            },
                            leadingIcon = { Icon(Icons.Default.ChevronLeft, null, modifier = Modifier.size(18.dp)) },
                        )
                        DropdownMenuItem(
                            text = { Text("下一个 →") },
                            onClick = {
                                showMenu = false
                                onSwipeRight()
                            },
                            leadingIcon = { Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            // Swipe indicator and controls for AI messages with multiple swipes
            if (!isUser && message.swipes != null && message.swipes.size > 1) {
                Row(
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .widthIn(max = 520.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onSwipeLeft,
                        modifier = Modifier.size(24.dp),
                        enabled = message.swipeIndex > 0,
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = "上一个回复",
                            modifier = Modifier.size(18.dp),
                            tint =
                                if (message.swipeIndex > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                        )
                    }
                    Text(
                        text = "${message.swipeIndex + 1}/${message.swipes.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = onSwipeRight,
                        modifier = Modifier.size(24.dp),
                        enabled = message.swipeIndex < message.swipes.size - 1,
                    ) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "下一个回复",
                            modifier = Modifier.size(18.dp),
                            tint =
                                if (message.swipeIndex < message.swipes.size - 1) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                        )
                    }
                }
            }
            // 头像已完全移除
        }
        // 项目②：API 实测用量 + 本地估算对照。仅在助手消息且网关确实上报了 usage 时渲染——
        // usage 为 null（网关不返回）时整行不出现，绝不用 0 或估算值冒充实测。
        if (!isUser) {
            message.usage?.let { usage ->
                val localEstimatedTokens = remember(displayedContent) { PromptBuilder.estimateTokensOf(displayedContent) }
                Text(
                    text = messageUsageLabel(usage, localEstimatedTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier =
                        Modifier.padding(
                            top = 4.dp,
                            start = 16.dp,
                            end = 0.dp,
                        ),
                )
            }
        }
        // Timestamp
        if (isLastInGroup && message.createdAt.isNotBlank()) {
            // 记忆化：同一消息的时间戳只格式化一次，重组时直接复用结果
            val timestamp = remember(message.createdAt) { formatTime(message.createdAt) }
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier =
                    Modifier.padding(
                        top = 4.dp,
                        start = if (isUser) 0.dp else 16.dp,
                        end = if (isUser) 16.dp else 0.dp,
                    ),
            )
        }
    }
}

/**
 * 单条消息用量标签（消息详情）：API 实测（输入/输出/总计 + 缓存命中率）+ 本地估算（该条正文）对照。
 * 缓存命中率只取自真实缓存字段（命中/未命中两字段之和 > 0 才算得出），缺失显示「未知」。
 */
private fun messageUsageLabel(
    usage: Usage,
    localEstimatedTokens: Int,
): String {
    val hitRateText = usage.cacheHitRateOrNull()?.let { "${(it * 100).toInt()}%" } ?: "未知"
    return "实测 输入 ${usage.promptTokens} · 输出 ${usage.completionTokens} · 总计 ${usage.totalTokens}" +
        " · 缓存 $hitRateText｜本地估算 ~$localEstimatedTokens"
}

/** 可复用的思维链折叠区，正式消息与流式占位消息保持同一交互。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun thinkingSection(
    thinking: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { onExpandedChange(!expanded) },
                    onLongClick = {},
                )
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "💭 思考过程",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            SelectionContainer {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 允许流式中的未闭合 <think> 标签实时显示，而不是等回复结束才出现。 */
internal fun splitThinkingForDisplay(reply: String): Pair<String, String?> {
    val open = reply.indexOf("<think>", ignoreCase = true)
    if (open < 0) return reply to null
    val contentStart = open + "<think>".length
    val close = reply.indexOf("</think>", contentStart, ignoreCase = true)
    return if (close >= 0) {
        val thinking = reply.substring(contentStart, close).trim()
        val content = (reply.removeRange(open, close + "</think>".length)).trim()
        content to thinking.ifBlank { null }
    } else {
        "" to reply.substring(contentStart).trim().ifBlank { null }
    }
}

private fun formatTime(isoString: String): String {
    return try {
        val instant = java.time.Instant.parse(isoString)
        val zonedDateTime = instant.atZone(java.time.ZoneId.systemDefault())
        zonedDateTime.format(TIME_FORMATTER)
    } catch (e: Exception) {
        ""
    }
}