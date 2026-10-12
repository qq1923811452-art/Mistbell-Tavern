package com.mistbell.tavern.android.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * API 配置数据类
 */
data class ApiConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    val isDefault: Boolean = false,
    val lastTestStatus: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val lastTestTime: Long = 0L,
    // 接口类型：openai / anthropic / gemini / custom
    val type: String = "openai",
    // 1M 上下文开关
    val context1M: Boolean = false,
    // SSE 流式传输开关
    val streamingEnabled: Boolean = true,
    // 禁用思考模式（true = 请求体发 thinking:{"type":"disabled"}；仅对 OpenAI 兼容网关生效）
    val thinkingDisabled: Boolean = false,
)

/**
 * 连接状态
 */
enum class ConnectionStatus {
    UNKNOWN, // 未测试
    TESTING, // 测试中
    SUCCESS, // 成功
    FAILED, // 失败
}

/**
 * 总览网格视图（长按预览模式复用；原堆叠页已收敛为 InlineApiCardsSection）
 */
@Suppress("FunctionNaming", "LongParameterList") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun OverviewGridView(
    apiConfigs: List<ApiConfig>,
    selectedIndex: Int,
    onCardClick: (Int) -> Unit,
    onEditApi: (ApiConfig) -> Unit,
    onDeleteApi: (String) -> Unit,
    modifier: Modifier = Modifier,
    cardAlpha: Float = 1f,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(apiConfigs, key = { it.id }) { apiConfig ->
            val index = apiConfigs.indexOf(apiConfig)
            val isSelected = index == selectedIndex
            ApiCardMini(
                apiConfig = apiConfig,
                isSelected = isSelected,
                onClick = { onCardClick(index) },
                onEdit = { onEditApi(apiConfig) },
                onDelete = { onDeleteApi(apiConfig.id) },
                alpha = cardAlpha,
            )
        }
    }
}

/**
 * 迷你 API 卡片（总览/预览模式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun ApiCardMini(
    apiConfig: ApiConfig,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    alpha: Float = 1f,
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(180.dp),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    (
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ).copy(alpha = alpha),
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (isSelected) 8.dp else 2.dp,
            ),
        border =
            if (isSelected) {
                BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                )
            } else {
                null
            },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶部
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = apiConfig.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = apiConfig.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 选中标记与更多菜单并排占位，避免右上角叠加
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "已选中",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "更多",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    onClick = {
                                        showMenu = false
                                        onEdit()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        showMenu = false
                                        onDelete()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                // 底部：状态和标记
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConnectionStatusChip(status = apiConfig.lastTestStatus)
                    if (apiConfig.isDefault) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "默认配置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 连接状态芯片（小版本）
 */
@Composable
fun ConnectionStatusChip(status: ConnectionStatus) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color =
            when (status) {
                ConnectionStatus.SUCCESS -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                ConnectionStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                ConnectionStatus.TESTING -> MaterialTheme.colorScheme.primaryContainer
                ConnectionStatus.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    when (status) {
                        ConnectionStatus.SUCCESS -> Icons.Default.CheckCircle
                        ConnectionStatus.FAILED -> Icons.Default.Error
                        ConnectionStatus.TESTING -> Icons.Default.Sync
                        ConnectionStatus.UNKNOWN -> Icons.Default.Help
                    },
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = statusTint(status),
            )
            Text(
                text =
                    when (status) {
                        ConnectionStatus.SUCCESS -> "正常"
                        ConnectionStatus.FAILED -> "失败"
                        ConnectionStatus.TESTING -> "测试中"
                        ConnectionStatus.UNKNOWN -> "未测试"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = statusTint(status),
            )
        }
    }
}

/**
 * 卡片指示器
 */
@Composable
fun CardIndicator(
    totalCards: Int,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalCards) { index ->
            val isSelected = index == currentIndex
            Box(
                modifier =
                    Modifier
                        .size(
                            width = if (isSelected) 24.dp else 8.dp,
                            height = 8.dp,
                        )
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            },
                        )
                        .animateContentSize(),
            )
        }
    }
}

/**
 * 空状态（全屏场景；设置页内联场景用 InlineApiCardsSection 的紧凑版）
 */
@Composable
fun EmptyApiState(onAdd: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "还没有 API 配置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "添加你的第一个 API 配置开始使用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加配置")
        }
    }
}

@Composable
private fun statusTint(status: ConnectionStatus): Color =
    when (status) {
        ConnectionStatus.SUCCESS -> Color(0xFF4CAF50)
        ConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
        ConnectionStatus.TESTING -> MaterialTheme.colorScheme.primary
        ConnectionStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
