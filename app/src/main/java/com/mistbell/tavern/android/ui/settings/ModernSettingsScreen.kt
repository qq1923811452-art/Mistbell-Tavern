package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.ui.common.ModernDivider
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import com.mistbell.tavern.android.ui.common.ModernSettingsItem
import com.mistbell.tavern.android.ui.common.ModernSwitchItem
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.ui.components.ContextTokenLimitSelector
import com.mistbell.tavern.android.ui.components.formatTokenLimit

/**
 * 主设置界面（现代化风格，唯一设置入口）。
 *
 * 原 SettingsScreen 的功能已全部并入本页（双轨 UI 收敛）：
 * 采样预设、请求超时、重试次数、上下文长度、深色模式、提示词模板、
 * 记忆提取提示词、向量记忆召回、全量备份/恢复、反馈日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Compose 屏幕级组件的既有形态
@Composable
fun ModernSettingsScreen(
    onBack: (() -> Unit)? = null,
    onNavigateToThemeManager: () -> Unit,
    onNavigateToPromptManagement: () -> Unit,
    onNavigateToVersionChangelog: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showAdvancedSettings by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // API 配置卡片区（左右滑动切换，长按预览所有配置）
    val apiConfigViewModel: ApiConfigViewModel = viewModel()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ModernTopBar(
                title = "设置",
                subtitle = "偏好与配置",
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ============ 基础设置（直接展示）============

            ApiSettingsSection(
                apiConfigViewModel = apiConfigViewModel,
            )

            EmbeddingApiSettingsSection()

            ConversationSettingsSection(viewModel)

            AppearanceSettingsSection(viewModel, onNavigateToThemeManager)

            MemorySettingsSection(viewModel, advanced = false)

            DataManagementSection(viewModel, isLoading)

            // ============ 高级设置（折叠）============

            ModernSettingsGroup(title = "⚙️ 高级设置") {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "高级功能",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "提示词、采样、记忆召回、网络与实验功能",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TextButton(onClick = { showAdvancedSettings = !showAdvancedSettings }) {
                        Text(if (showAdvancedSettings) "收起" else "展开")
                        Icon(
                            imageVector =
                                if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showAdvancedSettings,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptSettingsSection(onNavigateToPromptManagement)

                    MemorySettingsSection(viewModel, advanced = true)

                    SamplingPresetSection(viewModel)

                    AdvancedSamplingSection(viewModel)

                    ExperimentalFeaturesSection()
                }
            }

            AboutSection(
                onNavigateToVersionChangelog = onNavigateToVersionChangelog,
                onNavigateToAbout = onNavigateToAbout,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * API 设置区域（卡片堆叠式管理为主入口）
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级 section 的既有形态
@Composable
private fun ApiSettingsSection(
    apiConfigViewModel: ApiConfigViewModel,
) {
    ModernSettingsGroup(title = "🔧 AI 连接") {
        // 内联 API 卡片管理：左右滑动切换配置，长按预览所有配置
        InlineApiCardsSection(viewModel = apiConfigViewModel)
    }
}

/** 酒馆玩家常用的生成风格预设；新手使用平衡预设即可，不在首屏增加决策。 */
@Suppress("FunctionNaming")
@Composable
private fun SamplingPresetSection(viewModel: SettingsViewModel) {
    val samplingPreset by viewModel.samplingPreset.collectAsState()

    ModernSettingsGroup(title = "🎛️ 生成风格") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "采样预设",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = samplingPreset == "creative",
                    onClick = { viewModel.setSamplingPreset("creative") },
                    label = { Text("创意") },
                )
                FilterChip(
                    selected = samplingPreset == "balanced",
                    onClick = { viewModel.setSamplingPreset("balanced") },
                    label = { Text("平衡") },
                )
                FilterChip(
                    selected = samplingPreset == "precise",
                    onClick = { viewModel.setSamplingPreset("precise") },
                    label = { Text("精确") },
                )
                FilterChip(
                    selected = samplingPreset == "custom",
                    onClick = { viewModel.setSamplingPreset("custom") },
                    label = { Text("自定义") },
                )
            }
            if (samplingPreset == "custom") {
                Text(
                    text = "自定义参数请在 API 卡片编辑页的高级区域调整",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 对话设置区域
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ConversationSettingsSection(viewModel: SettingsViewModel) {
    val defaultContextTokens by viewModel.defaultContextTokens.collectAsState()

    ModernSettingsGroup(title = "💬 对话设置") {
        // 上下文长度：新会话的默认上下文 token 预算（与会话级设置共用同一组预设档位）
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "默认上下文长度",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "新会话的默认上下文 token 预算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatTokenLimit(defaultContextTokens),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ContextTokenLimitSelector(
                value = defaultContextTokens,
                // 全局页自身即真相源：组件不提供"跟随全局"档，回调值恒非空
                onValueChange = { value -> value?.let { viewModel.setDefaultContextTokens(it) } },
            )
        }
    }
}

/**
 * 提示词设置区域：自定义提示词管理入口 + 全局提示词模板（主提示词/用户名/人设/群聊规范）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun PromptSettingsSection(onNavigateToPromptManagement: () -> Unit) {
    val promptViewModel: CustomPromptViewModel = viewModel()
    val enabledPromptsCount by promptViewModel.enabledPromptsCount.collectAsState()

    ModernSettingsGroup(title = "📝 提示词管理") {
        ModernSettingsItem(
            title = "自定义提示词",
            subtitle = "管理破甲、文风等提示词 • 当前启用: $enabledPromptsCount 个",
            icon = Icons.Default.Edit,
            onClick = onNavigateToPromptManagement,
        )
    }

    // 全局提示词模板卡片（键常量与解析见 ChatSettingsResolver）
    PromptTemplateCard(viewModel())
}

/**
 * 外观设置区域
 */
@Suppress("FunctionNaming", "LongMethod") // Compose 屏幕级 section 的既有形态
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsSection(
    viewModel: SettingsViewModel,
    onNavigateToThemeManager: () -> Unit,
) {
    val darkMode by viewModel.darkMode.collectAsState()
    var darkModeExpanded by remember { mutableStateOf(false) }

    ModernSettingsGroup(title = "🎨 外观") {
        ModernSettingsItem(
            title = "主题管理",
            subtitle = "主题色、字体大小、消息样式",
            icon = Icons.Default.Palette,
            onClick = onNavigateToThemeManager,
        )

        ModernDivider()

        // 深色模式：跟随系统 / 浅色 / 深色
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "深色模式",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "跟随系统 / 手动切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExposedDropdownMenuBox(
                expanded = darkModeExpanded,
                onExpandedChange = { darkModeExpanded = it },
            ) {
                Surface(
                    onClick = { darkModeExpanded = true },
                    modifier =
                        Modifier
                            .menuAnchor()
                            .width(108.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Text(
                        text =
                            when (darkMode) {
                                "light" -> "浅色"
                                "dark" -> "深色"
                                else -> "跟随系统"
                            },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                ExposedDropdownMenu(
                    expanded = darkModeExpanded,
                    onDismissRequest = { darkModeExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("跟随系统") },
                        onClick = {
                            viewModel.setDarkMode("system")
                            darkModeExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("浅色") },
                        onClick = {
                            viewModel.setDarkMode("light")
                            darkModeExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("深色") },
                        onClick = {
                            viewModel.setDarkMode("dark")
                            darkModeExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * 记忆设置区域：默认长期记忆开关 + 记忆提取提示词 + 向量记忆召回（源 / top-k / 相似度阈值）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun MemorySettingsSection(
    viewModel: SettingsViewModel,
    advanced: Boolean,
) {
    val defaultLtmEnabled by viewModel.defaultLtmEnabled.collectAsState()
    var showMemoryPromptDialog by remember { mutableStateOf(false) }

    ModernSettingsGroup(title = "🧠 记忆") {
        ModernSwitchItem(
            title = "默认长期记忆（实验性）",
            subtitle = "新会话默认开启记忆抽取；向量记忆处于实验阶段",
            icon = Icons.Default.Psychology,
            checked = defaultLtmEnabled,
            onCheckedChange = { viewModel.setDefaultLtmEnabled(it) },
        )

        if (advanced) {
            ModernDivider()

            ModernSettingsItem(
                title = "记忆提取提示词",
                subtitle = "自定义长期记忆提取的提示词",
                icon = Icons.Default.Description,
                onClick = { showMemoryPromptDialog = true },
            )
        }
    }

    if (showMemoryPromptDialog) {
        MemoryExtractionPromptDialog(
            viewModel = viewModel,
            onDismiss = { showMemoryPromptDialog = false },
        )
    }

    if (advanced) {
        VectorMemorySettingsCard(viewModel = viewModel)
    }
}

/**
 * 数据管理区域（全量备份/恢复）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun DataManagementSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "安全提示：备份 ZIP 可能包含解密后的 API 密钥等敏感信息，文件本身未加密。请妥善保管，不要上传或分享给他人。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        DataBackupCard(viewModel = viewModel, isLoading = isLoading)
    }
}

/**
 * 高级采样参数区域：请求超时 + 重试次数
 */
@Suppress("FunctionNaming", "LongMethod", "MagicNumber") // Compose 屏幕级 section；0..5 档重试为设计常量
@Composable
private fun AdvancedSamplingSection(viewModel: SettingsViewModel) {
    val requestTimeout by viewModel.requestTimeout.collectAsState()
    val requestRetries by viewModel.requestRetries.collectAsState()

    ModernSettingsGroup(title = "🎛️ 采样参数") {
        // 请求超时：数值输入，范围 15..600 秒（越界自动收敛）
        var timeoutText by remember(requestTimeout) { mutableStateOf(requestTimeout.toString()) }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "请求超时",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "单次请求最长等待时间（15–600 秒）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { input ->
                    // 只允许数字；可解析且在范围内时立即落盘
                    if (input.all { it.isDigit() }) {
                        timeoutText = input.take(4)
                        input.toIntOrNull()?.let { if (it in 15..600) viewModel.setRequestTimeout(it) }
                    }
                },
                modifier = Modifier.width(96.dp),
                singleLine = true,
                trailingIcon = { Text("秒", style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(8.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
            )
        }

        ModernDivider()

        // 重试次数：失败后自动重试，范围 0..5
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "重试次数",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "请求失败后的自动重试上限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = requestRetries.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = requestRetries.toFloat(),
                onValueChange = { viewModel.setRequestRetries(it.toInt()) },
                valueRange = 0f..5f,
                steps = 4,
            )
        }
    }
}

/**
 * 实验性功能区域（功能就绪前仅展示说明，不提供死开关）
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ExperimentalFeaturesSection() {
    ModernSettingsGroup(title = "🔬 实验性功能") {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "函数调用、多模态等实验能力正在开发中，就绪前不开放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * 关于区域
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun AboutSection(
    onNavigateToVersionChangelog: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    var showCrashLogDialog by remember { mutableStateOf(false) }

    ModernSettingsGroup(title = "ℹ️ 关于") {
        ModernSettingsItem(
            title = "版本日志",
            subtitle = "查看版本更新记录",
            icon = Icons.Default.Description,
            onClick = onNavigateToVersionChangelog,
        )

        ModernDivider()

        ModernSettingsItem(
            title = "反馈日志",
            subtitle = "查看崩溃日志、导出反馈给开发者",
            icon = Icons.Default.Refresh,
            onClick = { showCrashLogDialog = true },
        )

        ModernDivider()

        ModernSettingsItem(
            title = "关于",
            subtitle = "应用信息、帮助文档",
            icon = Icons.Default.Info,
            onClick = onNavigateToAbout,
        )
    }

    if (showCrashLogDialog) {
        CrashLogDialog(onDismiss = { showCrashLogDialog = false })
    }
}
