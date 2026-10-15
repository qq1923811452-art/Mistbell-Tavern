package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.vector.EmbeddingRuntimeStatus

@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "MaxLineLength", "MagicNumber")
@Composable
fun ModernChatTopBar(
    chatTitle: String,
    displayCharacters: List<Character>,
    isScrolled: Boolean = false,
    onBackClick: () -> Unit,
    onPromptClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 项目②：会话级 API 实测用量聚合（来自 ChatViewModel.usageSummary）。
    // usageRounds = 0 表示本会话尚无任何带真实 usage 的助手消息（网关未上报）——
    // 此时不显示用量，而不是显示 0。
    usageRounds: Int = 0,
    usageTotalTokens: Int = 0,
    usageCacheHitRate: Double? = null,
    embeddingRuntimeStatus: EmbeddingRuntimeStatus? = null,
) {
    // 滚动时只增加很轻的层级，避免聊天内容被一条厚重阴影切开。
    val elevation by animateDpAsState(
        targetValue = if (isScrolled) 2.dp else 0.dp,
        label = "elevation",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = elevation,
        tonalElevation = if (isScrolled) 3.dp else 0.dp,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column {
            // 状态栏占位
            Spacer(modifier = Modifier.statusBarsPadding())
            // 主导航栏
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 返回按钮
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // 中间：头像 + 标题
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    // 复合头像
                    CompositeCharacterAvatar(
                        characters = displayCharacters,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    // 标题和副标题
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = chatTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            // 文字超出时显示省略号
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 副标题：参与者数量；有 API 实测用量时追加「实测 token + 缓存命中率」
                        if (displayCharacters.isNotEmpty()) {
                            Text(
                                text =
                                    buildString {
                                        append(
                                            if (displayCharacters.size > 1) {
                                                "${displayCharacters.size} 位参与者"
                                            } else {
                                                "单人对话"
                                            },
                                        )
                                        if (usageRounds > 0) {
                                            append(" · 实测 $usageTotalTokens tok")
                                            append(" · 缓存 ")
                                            append(usageCacheHitRate?.let { "${(it * 100).toInt()}%" } ?: "未知")
                                        }
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // 右侧按钮组
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 提示词预览按钮
                    IconButton(
                        onClick = onPromptClick,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "查看提示词",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 设置按钮
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Small live indicator: reports the initialized backend and only calls inference successful
            // after a real, validated vector has been returned by LiteRT-LM.
            if (embeddingRuntimeStatus?.source == "local-gemma") {
                val status = embeddingRuntimeStatus
                val badgeLabel =
                    when {
                        !status.initialized && status.errorMessage != null -> "本地 EmbeddingGemma 不可用"
                        status.backend == "NPU" && status.lastInferenceSucceeded == false ->
                            "NPU 推理失败 · 查看设置"
                        status.backend == "NPU" && status.lastInferenceSucceeded == true ->
                            "NPU 推理成功 · ${status.successfulInferenceCount} 次"
                        status.backend == "NPU" -> "NPU 已就绪 · 等待首次推理"
                        status.backend != null && status.lastInferenceSucceeded == false ->
                            "${status.backend} 推理失败 · NPU 未启用"
                        status.backend != null && status.lastInferenceSucceeded == true ->
                            "${status.backend} 推理成功 · NPU 未启用"
                        else -> "EmbeddingGemma 初始化中…"
                    }
                AssistChip(
                    onClick = onSettingsClick,
                    label = {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    leadingIcon = {
                        Text(
                            text = "●",
                            color =
                                when {
                                    !status.initialized -> MaterialTheme.colorScheme.error
                                    status.lastInferenceSucceeded == false -> MaterialTheme.colorScheme.error
                                    status.backend == "NPU" -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                        )
                    },
                    modifier = Modifier.padding(start = 60.dp, end = 12.dp, bottom = 6.dp),
                )
            }
            // 底部分割线（滚动时显示）
            if (isScrolled) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
