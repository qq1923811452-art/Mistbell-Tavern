package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_CLASSIC
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_GROUP
import kotlinx.coroutines.launch

private const val DROPDOWN_MAX_SCREEN_HEIGHT_FRACTION = 0.6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSetupScreen(
    initialCharacterId: String? = null,
    onBack: () -> Unit,
    onStartChat: (sessionId: String, characterIds: Set<String>) -> Unit,
    viewModel: ChatSetupViewModel =
        viewModel(
            factory =
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    LocalContext.current.applicationContext as android.app.Application,
                ),
        ),
) {
    val characters by viewModel.characters.collectAsState()
    val apiConfigs by viewModel.apiConfigs.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val selectedCharacterIds by viewModel.selectedCharacterIds.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val selectedProviderId by viewModel.selectedProviderId.collectAsState()
    val selectedWorldBookId by viewModel.selectedWorldBookId.collectAsState()
    val characterDefaultWorldBookId by viewModel.characterDefaultWorldBookId.collectAsState()
    val enableLongTermMemory by viewModel.enableLongTermMemory.collectAsState()
    val globalDefaultLtmEnabled by viewModel.globalDefaultLtmEnabled.collectAsState()
    val toast by viewModel.toast.collectAsState()

    var showProviderDropdown by remember { mutableStateOf(false) }
    var showWorldBookDropdown by remember { mutableStateOf(false) }
    val dropdownMaxHeight = (LocalConfiguration.current.screenHeightDp * DROPDOWN_MAX_SCREEN_HEIGHT_FRACTION).dp

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialCharacterId) {
        viewModel.initialize(initialCharacterId)
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(22.dp))
                        }
                        Text(
                            text = "创建聊天",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                // 经典模式：至少1个角色
                                // 群聊模式：至少2个角色，最多20个角色
                                when {
                                    selectedCharacterIds.isEmpty() -> {
                                        viewModel.showToast("请至少选择一个角色")
                                    }
                                    mode == SESSION_MODE_GROUP && selectedCharacterIds.size < MIN_GROUP_CHARACTERS -> {
                                        viewModel.showToast("群聊模式至少需要选择 $MIN_GROUP_CHARACTERS 个角色")
                                    }
                                    else -> {
                                        coroutineScope.launch {
                                            val sessionId = viewModel.getOrCreateSession(selectedCharacterIds)
                                            onStartChat(sessionId, selectedCharacterIds)
                                        }
                                    }
                                }
                            },
                            enabled = selectedCharacterIds.isNotEmpty(),
                        ) {
                            Text("开始聊天")
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 聊天模式选择：经典 / 群聊（「扮演」与 ④⑤ 骨架一律不露出——不做空入口纪律）。
            // 默认经典；经典模式下单选角色，群聊模式允许多选（见 ChatSetupViewModel.toggleCharacter）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "聊天模式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModeOption(
                            spec =
                                ModeSpec(
                                    label = "经典",
                                    description = "与单个角色一对一对话",
                                ),
                            selected = mode == SESSION_MODE_CLASSIC,
                            onClick = remember { { viewModel.setMode(SESSION_MODE_CLASSIC) } },
                            modifier = Modifier.weight(1f),
                        )
                        ModeOption(
                            spec =
                                ModeSpec(
                                    label = "群聊",
                                    description = "多角色轮流回应，发送 @名字 可指定谁接话",
                                ),
                            selected = mode == SESSION_MODE_GROUP,
                            onClick = remember { { viewModel.setMode(SESSION_MODE_GROUP) } },
                            modifier = Modifier.weight(1f),
                        )
                        // 模式②「扮演」占位（MODES.md 骨架预留的 UI 版）：先露入口、后实功能。
                        // 点击不写入 mode、不可创建，仅提示即将推出——绝不让用户创建出无叙事者链路的会话
                        ModeOption(
                            spec =
                                ModeSpec(
                                    label = "扮演",
                                    description = "由 AI 主持世界，你扮演角色",
                                    badge = "即将推出",
                                ),
                            selected = false,
                            onClick = remember { { viewModel.showToast("「扮演」模式即将推出，敬请期待") } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 选择角色
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text =
                            if (selectedCharacterIds.size > 1) {
                                "选择角色 (${selectedCharacterIds.size} 人群聊)"
                            } else {
                                "选择角色 (${selectedCharacterIds.size} 已选)"
                            },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // 群聊模式下给出参与者协作提示；经典模式单选无需说明
                    if (mode == SESSION_MODE_GROUP) {
                        Text(
                            text = "已选角色将作为群聊成员轮流回应（${MIN_GROUP_CHARACTERS}-${MAX_SELECTABLE_CHARACTERS} 个）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (characters.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                        ) {
                            Text(
                                "暂无角色，请先创建或导入角色",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            items(characters, key = { it.id }) { character ->
                CharacterCard(
                    character = character,
                    isSelected = selectedCharacterIds.contains(character.id),
                    onClick = { viewModel.toggleCharacter(character.id) },
                )
            }

            // 模型选择
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "选择模型",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    ExposedDropdownMenuBox(
                        expanded = showProviderDropdown,
                        onExpandedChange = { showProviderDropdown = it },
                    ) {
                        OutlinedTextField(
                            value = apiConfigs.find { it.id == selectedProviderId }?.name ?: "选择提供商",
                            onValueChange = {},
                            readOnly = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderDropdown) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        )

                        ExposedDropdownMenu(
                            modifier = Modifier.heightIn(max = dropdownMaxHeight),
                            expanded = showProviderDropdown,
                            onDismissRequest = { showProviderDropdown = false },
                        ) {
                            apiConfigs.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(provider.name, fontWeight = FontWeight.Medium)
                                            if (provider.model.isNotBlank()) {
                                                Text(
                                                    "模型: ${provider.model}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSelectedProvider(provider.id)
                                        showProviderDropdown = false
                                    },
                                    leadingIcon =
                                        if (selectedProviderId == provider.id) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // 世界书选择
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "世界书",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    val fieldValue =
                        if (selectedWorldBookId.isBlank()) {
                            "无"
                        } else {
                            worldBooks.find { it.id == selectedWorldBookId }?.name ?: "未知世界书"
                        }

                    ExposedDropdownMenuBox(
                        expanded = showWorldBookDropdown,
                        onExpandedChange = { showWorldBookDropdown = it },
                    ) {
                        OutlinedTextField(
                            value = fieldValue,
                            onValueChange = {},
                            readOnly = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showWorldBookDropdown) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        )

                        ExposedDropdownMenu(
                            modifier = Modifier.heightIn(max = dropdownMaxHeight),
                            expanded = showWorldBookDropdown,
                            onDismissRequest = { showWorldBookDropdown = false },
                        ) {
                            // "无" 选项
                            DropdownMenuItem(
                                text = { Text("无", fontWeight = FontWeight.Medium) },
                                onClick = {
                                    viewModel.setSelectedWorldBook("")
                                    showWorldBookDropdown = false
                                },
                                leadingIcon =
                                    if (selectedWorldBookId.isBlank()) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                            )

                            // 世界书列表
                            worldBooks.forEach { book ->
                                DropdownMenuItem(
                                    text = { Text(book.name.ifBlank { "未命名世界书" }) },
                                    onClick = {
                                        viewModel.setSelectedWorldBook(book.id)
                                        showWorldBookDropdown = false
                                    },
                                    leadingIcon =
                                        if (selectedWorldBookId == book.id) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // 长期记忆三态：跟随全局 / 开启 / 关闭（默认跟随，建会话不快照全局默认）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                    ) {
                        Text(
                            text = "长期记忆",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "保存对话内容到长期记忆中",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text =
                                if (enableLongTermMemory == null) {
                                    "跟随全局（当前：${if (globalDefaultLtmEnabled) "开" else "关"}）"
                                } else {
                                    "当前生效：${if (enableLongTermMemory == true) "开" else "关"}"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = enableLongTermMemory == null,
                                onClick = { viewModel.setLongTermMemory(null) },
                                label = { Text("跟随全局") },
                            )
                            FilterChip(
                                selected = enableLongTermMemory == true,
                                onClick = { viewModel.setLongTermMemory(true) },
                                label = { Text("开启") },
                            )
                            FilterChip(
                                selected = enableLongTermMemory == false,
                                onClick = { viewModel.setLongTermMemory(false) },
                                label = { Text("关闭") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterCard(
    character: com.mistbell.tavern.android.data.api.model.Character,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        border =
            if (isSelected) {
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
            } else {
                null
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 头像
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            character.color?.let {
                                try {
                                    Color(android.graphics.Color.parseColor(it))
                                } catch (_: Exception) {
                                    MaterialTheme.colorScheme.primary
                                }
                            } ?: MaterialTheme.colorScheme.primary,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = character.name.take(1),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (character.description.isNotBlank()) {
                    Text(
                        text = character.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// 聊天模式选项卡（分段按钮）：选中态用 secondaryContainer + 描边高亮，样式从简。
// 用 Box + clickable 而非 Surface(onClick)——避免依赖版本相关的实验性 M3 API
// 模式选项的展示规格；badge 非空 = 未实装占位（置灰 + 不可创建）
private data class ModeSpec(
    val label: String,
    val description: String,
    val badge: String? = null,
)

// 占位模式置灰透明度
private const val DISABLED_MODE_ALPHA = 0.55f

@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ModeOption(
    spec: ModeSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = spec.label
    val description = spec.description
    val badge = spec.badge
    // 占位模式（带徽标=未实装）：置灰展示，点击由调用方决定（toast 提示，不写入 mode）
    val enabled = badge == null
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier =
            modifier
                .clip(shape)
                // 未实装的占位模式置灰展示（即将推出）：可见但不可选，杜绝"创建出行为不确定的会话"
                .alpha(if (enabled) 1f else DISABLED_MODE_ALPHA)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    shape = shape,
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ModeOptionLabelRow(label = label, selected = selected, badge = badge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ModeOptionLabelRow(
    label: String,
    selected: Boolean,
    badge: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        badge?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
