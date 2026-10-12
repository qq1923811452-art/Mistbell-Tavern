package com.mistbell.tavern.android.ui.chatlist

import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.ui.chat.CompositeCharacterAvatar
import com.mistbell.tavern.android.util.SessionExportFormat
import com.mistbell.tavern.android.util.SessionExportResult
import com.mistbell.tavern.android.util.SessionExporter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 现代化会话列表项组件
 *
 * 设计特点：
 * - 更大的头像和更舒适的间距
 * - 渐变背景突出未读状态
 * - 流畅的动画效果
 * - 清晰的信息层次
 *
 * 长按弹出操作菜单（多选/置顶/标记已读/改名/复制/导出/删除）及对应确认 dialog。
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModernChatListItem(
    item: ChatListItem,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onEnterMultiSelect: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onMarkAsRead: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
    onExport: (SessionExportFormat, String, (SessionExportResult?) -> Unit) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showBottomSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFileName by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    // 动画效果
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.95f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "scale",
    )

    val hasUnread = item.unreadCount > 0
    val backgroundColor =
        when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            hasUnread -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surface
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .scale(scale),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        shadowElevation = 0.dp,
        tonalElevation = if (isSelected || hasUnread) 2.dp else 0.dp,
        border =
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            if (!isMultiSelectMode) {
                                showBottomSheet = true // 长按弹出操作菜单
                            }
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 头像（左侧）
            Box {
                CompositeCharacterAvatar(
                    characters = item.participantCharacters,
                    modifier = Modifier.size(56.dp),
                )

                // 未读徽章
                if (hasUnread) {
                    Badge(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp),
                        containerColor = MaterialTheme.colorScheme.error,
                    ) {
                        Text(
                            text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // 置顶图标
                if (item.isPinned) {
                    Surface(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 4.dp, y = 4.dp)
                                .size(20.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "已置顶",
                            modifier =
                                Modifier
                                    .padding(4.dp)
                                    .size(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            // 中间内容区域
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.sessionTitle.ifBlank { item.characterName },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    // 时间戳
                    Text(
                        text = item.lastMessageTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 最后一条消息预览
                if (item.lastMessage.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 如果有发送者，显示发送者名称
                        if (item.lastMessageSender.isNotBlank()) {
                            Text(
                                text = "${item.lastMessageSender}:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }

                        Text(
                            text = item.lastMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 元数据行：模式标签
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 群聊标签
                    if (item.participantCharacters.size > 1) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = "群聊 ${item.participantCharacters.size}人",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }

                    // 静音标签
                    if (item.isMuted) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "静音",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 与角色卡一致：显式菜单入口和长按共用同一个操作抽屉。
            if (!isMultiSelectMode) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ) {
                    IconButton(
                        onClick = { showBottomSheet = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "会话操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 选中指示器
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    // 长按操作菜单（仅普通模式）
    if (showBottomSheet && !isMultiSelectMode) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
            ) {
                // 多选
                ListItem(
                    headlineContent = { Text("多选") },
                    leadingContent = {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showBottomSheet = false
                            onEnterMultiSelect()
                        },
                )

                // 置顶/取消置顶
                ListItem(
                    headlineContent = { Text(if (item.isPinned) "取消置顶" else "置顶") },
                    leadingContent = {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showBottomSheet = false
                            onTogglePin()
                        },
                )

                // 标记为已读
                if (item.unreadCount > 0) {
                    ListItem(
                        headlineContent = { Text("标记为已读") },
                        leadingContent = {
                            Icon(
                                Icons.Default.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                showBottomSheet = false
                                onMarkAsRead()
                            },
                    )
                }

                // 改名
                ListItem(
                    headlineContent = { Text("改名") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showBottomSheet = false
                            newName = item.sessionTitle.ifBlank { item.characterName }
                            showRenameDialog = true
                        },
                )

                // 复制
                ListItem(
                    headlineContent = { Text("复制") },
                    leadingContent = {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showBottomSheet = false
                            onCopy()
                        },
                )

                // 导出
                ListItem(
                    headlineContent = { Text("导出") },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showBottomSheet = false
                            exportFileName =
                                SessionExporter.buildFileName(
                                    item.sessionTitle.ifBlank { item.characterName },
                                    item.sessionId,
                                    SessionExportFormat.JSON.extension,
                                )
                            showExportDialog = true
                        },
                )

                HorizontalDivider()

                // 删除
                ListItem(
                    headlineContent = {
                        Text(
                            "删除",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showBottomSheet = false
                            showDeleteDialog = true
                        },
                )
            }
        }
    }

    // 改名 dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("改名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
                    placeholder = { Text(item.characterName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        onRename(newName)
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 导出 dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val exportFormatLabel =
                        if (exportFileName.endsWith(".${SessionExportFormat.JSONL.extension}", ignoreCase = true)) {
                            "格式：酒馆 JSONL"
                        } else {
                            "格式：JSON"
                        }
                    Text(
                        text = exportFormatLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = exportFileName,
                        onValueChange = { exportFileName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存到：${SessionExporter.displayLocation(exportFileName)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalName =
                            exportFileName.trim().ifBlank {
                                SessionExporter.buildFileName(
                                    item.sessionTitle.ifBlank { item.characterName },
                                    item.sessionId,
                                    SessionExportFormat.JSON.extension,
                                )
                            }
                        val format =
                            if (finalName.endsWith(".${SessionExportFormat.JSONL.extension}", ignoreCase = true)) {
                                SessionExportFormat.JSONL
                            } else {
                                SessionExportFormat.JSON
                            }
                        showExportDialog = false
                        onExport(format, finalName, ) { result ->
                            result?.let {
                                Toast.makeText(context, "已保存到 ${it.location}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 删除确认 dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除") },
            text = { Text("确定要删除这个会话吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 格式化时间戳为友好显示
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "刚刚" // 1分钟内
        diff < 3600_000 -> "${diff / 60_000}分钟前" // 1小时内
        diff < 86400_000 -> "${diff / 3600_000}小时前" // 24小时内
        diff < 172800_000 -> "昨天" // 48小时内
        diff < 604800_000 -> "${diff / 86400_000}天前" // 7天内
        else -> SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
    }
}
