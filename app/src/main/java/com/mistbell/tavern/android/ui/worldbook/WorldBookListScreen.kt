package com.mistbell.tavern.android.ui.worldbook

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.ui.common.ModernTopBar
import com.mistbell.tavern.android.data.repository.WorldBookScanSettingsRepository
import com.mistbell.tavern.android.ui.components.*
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap
import com.mistbell.tavern.android.util.WorldBookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookListScreen(
    onBack: () -> Unit = {},
    // 可空：传入则导航外跳；为 null（默认/导航图当前用法）则在本屏内联展开条目列表。
    // 不能用 `onBookClick: (String)->Unit = {}` 再拿 `!= {}` 判断——每个 {} 都是新实例，
    // 比较恒为 true，会导致点击永远走空操作分支、进不去条目（见下方 clickable）。
    onBookClick: ((String) -> Unit)? = null,
    viewModel: WorldBookEditorViewModel = viewModel(),
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val worldBooks by viewModel.worldBooks.collectAsState()
    val selectedBookId by viewModel.selectedBookId.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val showEntryForm by viewModel.showEntryForm.collectAsState()
    val entryForm by viewModel.entryForm.collectAsState()
    val message by viewModel.message.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val recursiveScanEnabled by viewModel.recursiveScanEnabled.collectAsState()
    val recursiveScanDepth by viewModel.recursiveScanDepth.collectAsState()

    var showNewBookDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }
    var showDeleteBookDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteEntryDialog by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 导入世界书：SAF 选 JSON → WorldBookParser 解析 → 落库（参照角色导入的提示风格）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            try {
                                val jsonString =
                                    context.contentResolver.openInputStream(uri)
                                        ?.bufferedReader()?.use { it.readText() }
                                if (jsonString != null) {
                                    // 兜底书名：取文件显示名去后缀，取不到用固定文案
                                    val fallbackName =
                                        queryDisplayName(context, uri)
                                            ?.substringBeforeLast('.')
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "导入的世界书"
                                    WorldBookParser.parse(jsonString, fallbackName)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("WorldBookImport", "导入世界书失败", e)
                                null
                            }
                        }
                    if (result != null) {
                        val (book, entryList) = result
                        try {
                            withContext(Dispatchers.IO) {
                                val db = TavernApplication.instance.container.database
                                db.worldBookDao().upsertBook(book)
                                if (entryList.isNotEmpty()) {
                                    db.worldBookDao().upsertEntries(entryList)
                                }
                            }
                            snackbarHostState.showSnackbar(
                                "成功导入世界书：${book.name}（${entryList.size} 条条目）",
                            )
                            // 刷新列表
                            viewModel.loadFromServer()
                        } catch (e: Exception) {
                            android.util.Log.e("WorldBookImport", "保存世界书失败", e)
                            snackbarHostState.showSnackbar("导入失败: ${e.message}")
                        }
                    } else {
                        snackbarHostState.showSnackbar("导入失败：无法解析世界书文件")
                    }
                }
            }
        }

    // Back handler: if viewing entries, go back to book list; otherwise exit
    BackHandler {
        if (selectedBookId != null) viewModel.clearSelectedBook() else onBack()
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(Unit) { viewModel.loadFromServer() }

    val selectedBook = worldBooks.find { it.id == selectedBookId }
    val totalEntries = worldBooks.sumOf { it.entries.size }
    val activeEntries = worldBooks.sumOf { book -> book.entries.count { !it.disable } }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .clearFocusOnTap(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ModernTopBar(
                    title = selectedBook?.name ?: "世界书",
                    subtitle = "管理你的世界书",
                    onBack =
                        if (showBackButton || selectedBookId != null) {
                            {
                                if (selectedBookId != null) viewModel.clearSelectedBook() else onBack()
                            }
                        } else {
                            null
                        },
                    actions = {
                        if (selectedBookId != null) {
                            TextButton(onClick = { viewModel.showNewEntryForm() }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加条目")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    importLauncher.launch(
                                        arrayOf("application/json", "application/octet-stream"),
                                    )
                                },
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = "导入世界书")
                            }
                        }
                        // Refresh button
                        IconButton(onClick = { viewModel.loadFromServer() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(18.dp))
                        }
                    },
                )

                // Search bar (only show when not in entry list view)
                if (selectedBookId == null) {
                    SearchBar(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = "搜索世界书名称或条目",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedBookId == null) {
                ExtendedFloatingActionButton(
                    onClick = { showNewBookDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("新建世界书") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding(),
        ) {
            if (selectedBookId == null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("递归扫描", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "允许已激活的世界书继续触发其他条目",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = recursiveScanEnabled,
                                onCheckedChange = viewModel::setRecursiveScanEnabled,
                            )
                        }
                        if (recursiveScanEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "最大扫描深度：$recursiveScanDepth",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            var pendingDepth by remember(recursiveScanDepth) {
                                mutableStateOf(recursiveScanDepth.toFloat())
                            }
                            val minRecursionDepth = WorldBookScanSettingsRepository.MIN_RECURSION_DEPTH.toFloat()
                            val maxRecursionDepth = WorldBookScanSettingsRepository.MAX_RECURSION_DEPTH.toFloat()
                            Slider(
                                value = pendingDepth,
                                onValueChange = { pendingDepth = it },
                                valueRange = minRecursionDepth..maxRecursionDepth,
                                steps =
                                    WorldBookScanSettingsRepository.MAX_RECURSION_DEPTH -
                                        WorldBookScanSettingsRepository.MIN_RECURSION_DEPTH - 1,
                                onValueChangeFinished = {
                                    viewModel.setRecursiveScanDepth(
                                        pendingDepth.toInt().coerceIn(
                                            WorldBookScanSettingsRepository.MIN_RECURSION_DEPTH,
                                            WorldBookScanSettingsRepository.MAX_RECURSION_DEPTH,
                                        ),
                                    )
                                },
                            )
                            Text(
                                "深度越高，设定联动越完整，但可能激活更多内容并增加 Token",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Book list view
                if (worldBooks.isEmpty()) {
                    // Empty state - centered
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyStateView("📖", "暂无世界书", "点击右下角 + 创建")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(worldBooks, key = { it.id }) { book ->
                            Card(
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        val cb = onBookClick
                                        if (cb != null) {
                                            cb(book.id)
                                        } else {
                                            viewModel.selectBook(book.id)
                                        }
                                    },
                                shape = MaterialTheme.shapes.medium,
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                border =
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(book.name, fontWeight = FontWeight.Medium)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "${book.entries.size} 个条目",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                "·",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            val activeCount = book.entries.count { !it.disable }
                                            Text(
                                                "$activeCount 个启用",
                                                style = MaterialTheme.typography.bodySmall,
                                                color =
                                                    if (activeCount > 0) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showDeleteBookDialog = book.id }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Entry list view
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 统计信息 - 三列布局
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // 总计
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small,
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "${entries.size}",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "总计",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // 启用
                            val activeCount = entries.count { !it.disable }
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small,
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "$activeCount",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        "启用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }

                            // 禁用
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small,
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                    ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "${entries.size - activeCount}",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "禁用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (entries.isEmpty()) {
                        item { EmptyStateView("📝", "暂无条目", "点击右上角 + 添加") }
                    }

                    items(entries, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (entry.disable) {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 左侧内容 - 限制最大宽度，预留右侧空间
                                    Column(
                                        modifier = Modifier.weight(1f, fill = false),
                                    ) {
                                        Text(
                                            entry.comment.ifBlank { "未命名条目" },
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color =
                                                if (entry.disable) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                        )
                                        if (entry.key.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "关键词: ${entry.key.joinToString(", ")}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        // 位置摘要：@D 档显示 "[角色] @D深度"（对齐酒馆条目上的 @D 徽标）
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            entryPlacementSummary(entry.insertPosition, entry.depth, entry.depthRole),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        // 显示内容预览
                                        if (entry.content.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                entry.content,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // 右侧固定区域 - 常量标签 + 开关
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 常量标签占位 - 即使不显示也占用空间
                                        Box(
                                            modifier = Modifier.width(56.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (entry.constant) {
                                                AssistChip(
                                                    onClick = {},
                                                    label = {
                                                        Text(
                                                            "常量",
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                        // 开关
                                        Switch(
                                            checked = !entry.disable,
                                            onCheckedChange = { enabled ->
                                                viewModel.updateEntry(
                                                    entry.id,
                                                    kotlinx.serialization.json.buildJsonObject {
                                                        put("disable", kotlinx.serialization.json.JsonPrimitive(!enabled))
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = { viewModel.showEditEntryForm(entry) }) {
                                        Text("编辑")
                                    }
                                    TextButton(onClick = { showDeleteEntryDialog = entry.id }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // New world book dialog
    if (showNewBookDialog) {
        AlertDialog(
            onDismissRequest = { showNewBookDialog = false },
            title = { Text("新建世界书") },
            text = {
                FormTextField(
                    value = newBookName,
                    onValueChange = { newBookName = it },
                    label = "名称",
                    placeholder = "世界书名称",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newBookName.isNotBlank()) {
                        viewModel.createWorldBook(newBookName)
                        newBookName = ""
                        showNewBookDialog = false
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewBookDialog = false }) { Text("取消") }
            },
        )
    }

    // Delete book confirmation
    showDeleteBookDialog?.let { bookId ->
        ConfirmDeleteDialog(
            message = "确定要删除这个世界书及其所有条目吗？",
            onConfirm = {
                viewModel.deleteWorldBook(bookId)
                showDeleteBookDialog = null
            },
            onDismiss = { showDeleteBookDialog = null },
        )
    }

    // Delete entry confirmation
    showDeleteEntryDialog?.let { entryId ->
        ConfirmDeleteDialog(
            message = "确定要删除这个条目吗？",
            onConfirm = {
                viewModel.deleteEntry(entryId)
                showDeleteEntryDialog = null
            },
            onDismiss = { showDeleteEntryDialog = null },
        )
    }

    // Entry form bottom sheet
    if (showEntryForm) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.hideEntryForm() },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // 表单字段较高（名称/关键词/内容/位置/深度/概率），小屏上不滚动会挤掉底部按钮
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (viewModel.editingEntryId.value != null) "编辑条目" else "新建条目",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                FormTextField(
                    value = entryForm.comment,
                    onValueChange = { viewModel.updateEntryForm { copy(comment = it) } },
                    label = "名称",
                    placeholder = "条目名称",
                )
                FormTextField(
                    value = entryForm.keys,
                    onValueChange = { viewModel.updateEntryForm { copy(keys = it) } },
                    label = "关键词（逗号分隔）",
                    placeholder = "关键词1, 关键词2",
                )
                FormTextArea(
                    value = entryForm.content,
                    onValueChange = { viewModel.updateEntryForm { copy(content = it) } },
                    label = "内容",
                    placeholder = "世界书条目内容...",
                    minLines = 4,
                )

                // Insert position（酒馆式 9 选项：6 锚点 + [系统/用户/AI] 插入深度 @D）
                WorldBookPlacementField(
                    insertPosition = entryForm.insertPosition,
                    depth = entryForm.depth,
                    depthRole = entryForm.depthRole,
                ) { position, depth, role ->
                    viewModel.updateEntryForm { copy(insertPosition = position, depth = depth, depthRole = role) }
                }

                // Depth：仅 @D 档（深度≥1）时显示（插入历史倒数第 D 条之前）
                if (entryForm.depth > 0) {
                    var depthText by remember(entryForm.depth) { mutableStateOf(entryForm.depth.toString()) }
                    FormTextField(
                        value = depthText,
                        onValueChange = {
                            depthText = it
                            it.toIntOrNull()?.let { d -> viewModel.updateEntryForm { copy(depth = d.coerceIn(0, 10)) } }
                        },
                        label = "插入深度 @D (1-10)",
                        placeholder = "历史倒数第D条之前",
                    )
                }

                // Probability：0-1，关键词命中后仍按概率掷骰；1=必触发，常驻条目不受影响
                var probabilityText by remember(entryForm.probability) {
                    mutableStateOf(entryForm.probability.toString())
                }
                FormTextField(
                    value = probabilityText,
                    onValueChange = {
                        probabilityText = it
                        it.toDoubleOrNull()?.let { p ->
                            val clamped = p.coerceIn(MIN_PROBABILITY, MAX_PROBABILITY)
                            viewModel.updateEntryForm { copy(probability = clamped) }
                        }
                    },
                    label = "触发概率 (0-1)",
                    placeholder = "1",
                )

                // Toggles
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = !entryForm.disable, onCheckedChange = { viewModel.updateEntryForm { copy(disable = !it) } })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("启用", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = entryForm.constant, onCheckedChange = { viewModel.updateEntryForm { copy(constant = it) } })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("常量", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Button(
                    onClick = { viewModel.saveEntry() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("保存") }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/** 查询 SAF 文件的显示名（用于世界书导入的书名兜底），失败返回 null */
private fun queryDisplayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}
