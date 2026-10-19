package com.mistbell.tavern.v3.feature.worldbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { WorldBookLibraryStore(File(context.filesDir, "worldbooks")) }
    val settings = remember(context) {
        context.getSharedPreferences("mistbell-v3-settings", android.content.Context.MODE_PRIVATE)
    }
    var activeBookId by remember(settings) {
        mutableStateOf(settings.getString("active_worldbook_id", "") ?: "")
    }
    var books by remember { mutableStateOf(emptyList<WorldBook>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editingEntryId by remember { mutableStateOf<String?>(null) }
    var showBookDialog by remember { mutableStateOf(false) }
    var showEntryDialog by remember { mutableStateOf(false) }
    var deleteBook by remember { mutableStateOf<WorldBook?>(null) }
    var newBookName by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var keys by remember { mutableStateOf("") }
    var secondaryKeys by remember { mutableStateOf("") }
    var orderText by remember { mutableStateOf("0") }
    var insertPosition by remember { mutableStateOf(WorldBookPlacement.POSITION_BEFORE_PROMPT) }
    var depthText by remember { mutableStateOf("0") }
    var probabilityText by remember { mutableStateOf("100") }
    var depthRole by remember { mutableStateOf(WorldBookPlacement.DEPTH_ROLE_SYSTEM) }
    var groupName by remember { mutableStateOf("") }
    var sticky by remember { mutableStateOf(false) }
    var cooldownText by remember { mutableStateOf("0") }
    var delayText by remember { mutableStateOf("0") }
    var content by remember { mutableStateOf("") }
    var constant by remember { mutableStateOf(false) }
    var disabled by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("本机世界书；支持 SillyTavern World Info JSON 导入导出。") }
    val selected = books.firstOrNull { it.id == selectedId }

    fun refresh(select: String? = selectedId) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.list() } }
                .onSuccess { books = it; selectedId = select?.takeIf { id -> it.any { book -> book.id == id } } }
                .onFailure { status = "读取失败：" + (it.message ?: it::class.java.simpleName) }
        }
    }

    fun editEntry(entry: WorldBookEntry?) {
        editingEntryId = entry?.id
        comment = entry?.comment.orEmpty()
        keys = entry?.keys.orEmpty().joinToString(", ")
        secondaryKeys = entry?.secondaryKeys.orEmpty().joinToString(", ")
        orderText = (entry?.order ?: 0).toString()
        insertPosition = entry?.insertPosition ?: WorldBookPlacement.POSITION_BEFORE_PROMPT
        depthText = (entry?.depth ?: 0).toString()
        probabilityText = ((entry?.probability ?: 1.0) * 100).toInt().toString()
        depthRole = entry?.depthRole ?: WorldBookPlacement.DEPTH_ROLE_SYSTEM
        groupName = entry?.groupName.orEmpty()
        sticky = entry?.sticky ?: false
        cooldownText = (entry?.cooldown ?: 0).toString()
        delayText = (entry?.delay ?: 0).toString()
        content = entry?.content.orEmpty()
        constant = entry?.constant ?: false
        disabled = entry?.disable ?: false
        showEntryDialog = true
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 5 * 1024 * 1024) { "世界书 JSON 超过 5 MiB。" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                }
                val parsed = WorldBookParser.parse(raw, "导入的世界书") ?: error("JSON 格式无效")
                withContext(Dispatchers.IO) { store.save(parsed.copy(id = UUID.randomUUID().toString())) }
                parsed
            }.onSuccess { status = "已导入：" + it.name; refresh() }
                .onFailure { status = "导入失败：" + (it.message ?: it::class.java.simpleName) }
        }
    }

    val exportTarget = remember { mutableStateOf<WorldBook?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val target = exportTarget.value
        exportTarget.value = null
        if (uri != null && target != null) scope.launch {
            runCatching {
                val json = store.export(target)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法写入文件")
                }
            }.onSuccess { status = "已导出：" + target.name }
                .onFailure { status = "导出失败：" + (it.message ?: it::class.java.simpleName) }
        }
    }

    LaunchedEffect(Unit) { refresh(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(selected?.name ?: "世界书管理") },
            navigationIcon = {
                TextButton(onClick = { if (selectedId != null) selectedId = null else onBack() }) { Text("返回") }
            },
            actions = {
                if (selected != null) TextButton(onClick = { editEntry(null) }) { Text("添加条目") }
                else TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "*/*")) }) { Text("导入") }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text(status, style = MaterialTheme.typography.bodyMedium) }
            if (selected == null) {
                item {
                    Button(onClick = { newBookName = ""; showBookDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("新建世界书")
                    }
                }
                items(books, key = WorldBook::id) { book ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(book.name, style = MaterialTheme.typography.titleMedium)
                            Text(book.entries.size.toString() + " 个条目 · " + book.entries.count { !it.disable } + " 个启用")
                            Text(
                                if (activeBookId == book.id) "聊天启用中（仅此一本外部世界书）" else "未在聊天中启用",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                TextButton(onClick = { selectedId = book.id }) { Text("管理条目") }
                                TextButton(onClick = {
                                    activeBookId = if (activeBookId == book.id) "" else book.id
                                    settings.edit().apply {
                                        if (activeBookId.isBlank()) remove("active_worldbook_id")
                                        else putString("active_worldbook_id", activeBookId)
                                    }.apply()
                                    status = if (activeBookId == book.id) "聊天已启用世界书：${book.name}" else "已停用外部世界书"
                                }) { Text(if (activeBookId == book.id) "停用聊天" else "启用聊天") }
                                TextButton(onClick = { exportTarget.value = book; exportLauncher.launch(book.name + ".json") }) { Text("导出") }
                                TextButton(onClick = { deleteBook = book }) { Text("删除") }
                            }
                        }
                    }
                }
            } else {
                items(selected.entries.sortedBy { it.order }, key = WorldBookEntry::id) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(entry.comment.ifBlank { entry.keys.joinToString(", ").ifBlank { "未命名条目" } },
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (entry.disable) "已停用" else if (entry.constant) "常驻条目" else "关键词触发")
                            Text(entry.content, maxLines = 3)
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                store.save(selected.copy(entries = selected.entries.map {
                                                    if (it.id == entry.id) it.copy(disable = !it.disable) else it
                                                }))
                                            }
                                        }.onSuccess { refresh(selected.id); status = "条目状态已更新" }
                                            .onFailure { status = "更新失败：" + (it.message ?: it::class.java.simpleName) }
                                    }
                                }) { Text(if (entry.disable) "启用" else "停用") }
                                TextButton(onClick = { editEntry(entry) }) { Text("编辑") }
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                store.save(selected.copy(entries = selected.entries.filterNot { it.id == entry.id }))
                                            }
                                        }.onSuccess { refresh(selected.id); status = "条目已删除" }
                                            .onFailure { status = "删除失败：" + (it.message ?: it::class.java.simpleName) }
                                    }
                                }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBookDialog) AlertDialog(
        onDismissRequest = { showBookDialog = false },
        title = { Text("新建世界书") },
        text = { OutlinedTextField(newBookName, { newBookName = it }, label = { Text("名称") }) },
        confirmButton = {
            TextButton(enabled = newBookName.isNotBlank(), onClick = {
                showBookDialog = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { store.save(WorldBook(UUID.randomUUID().toString(), newBookName.trim(), emptyList())) }
                    }.onSuccess { refresh(it.id); status = "世界书已创建" }
                        .onFailure { status = "创建失败：" + (it.message ?: it::class.java.simpleName) }
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = { showBookDialog = false }) { Text("取消") } },
    )

    if (showEntryDialog && selected != null) AlertDialog(
        onDismissRequest = { showEntryDialog = false },
        title = { Text(if (editingEntryId == null) "新建条目" else "编辑条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(comment, { comment = it }, label = { Text("条目名称") })
                OutlinedTextField(keys, { keys = it }, label = { Text("关键词（逗号分隔）") })
                OutlinedTextField(secondaryKeys, { secondaryKeys = it }, label = { Text("二级关键词（逗号分隔）") })
                OutlinedTextField(orderText, { orderText = it.filter(Char::isDigit).take(6) }, label = { Text("插入顺序") })
                OutlinedTextField(insertPosition, { insertPosition = it }, label = { Text("插入位置（before_prompt / after_prompt / an_before / an_after / em_before / em_after）") })
                OutlinedTextField(depthText, { depthText = it.filter(Char::isDigit).take(2) }, label = { Text("深度（0-10，0 表示非深度插入）") })
                OutlinedTextField(probabilityText, { probabilityText = it.filter(Char::isDigit).take(3) }, label = { Text("触发概率（0-100%）") })
                OutlinedTextField(depthRole, { depthRole = it }, label = { Text("深度角色（system / user / assistant）") })
                OutlinedTextField(groupName, { groupName = it }, label = { Text("互斥组名称（可选）") })
                OutlinedTextField(cooldownText, { cooldownText = it.filter(Char::isDigit).take(4) }, label = { Text("冷却轮数") })
                OutlinedTextField(delayText, { delayText = it.filter(Char::isDigit).take(4) }, label = { Text("延迟轮数") })
                Row {
                    Checkbox(sticky, { sticky = it })
                    Text("粘性触发", modifier = Modifier.padding(top = 12.dp))
                }
                OutlinedTextField(content, { content = it }, label = { Text("内容") }, minLines = 4)
                Row {
                    Checkbox(constant, { constant = it })
                    Text("常驻注入", modifier = Modifier.padding(top = 12.dp))
                }
                Row {
                    Checkbox(disabled, { disabled = it })
                    Text("停用条目", modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entry = WorldBookEntry(
                    id = editingEntryId ?: UUID.randomUUID().toString(),
                    bookId = selected.id,
                    comment = comment.trim(),
                    keys = keys.split(',').map(String::trim).filter(String::isNotEmpty),
                    content = content,
                    constant = constant,
                    disable = disabled,
                    secondaryKeys = secondaryKeys.split(',').map(String::trim).filter(String::isNotEmpty),
                    order = orderText.toIntOrNull()?.coerceIn(0, 999999) ?: 0,
                    insertPosition = insertPosition.takeIf { it in setOf(
                        WorldBookPlacement.POSITION_BEFORE_PROMPT,
                        WorldBookPlacement.POSITION_AFTER_PROMPT,
                        WorldBookPlacement.POSITION_AN_BEFORE,
                        WorldBookPlacement.POSITION_AN_AFTER,
                        WorldBookPlacement.POSITION_EM_BEFORE,
                        WorldBookPlacement.POSITION_EM_AFTER,
                    ) } ?: WorldBookPlacement.POSITION_BEFORE_PROMPT,
                    depth = depthText.toIntOrNull()?.coerceIn(0, 10) ?: 0,
                    probability = (probabilityText.toIntOrNull() ?: 100).coerceIn(0, 100) / 100.0,
                    depthRole = depthRole.takeIf { it in setOf(
                        WorldBookPlacement.DEPTH_ROLE_SYSTEM,
                        WorldBookPlacement.DEPTH_ROLE_USER,
                        WorldBookPlacement.DEPTH_ROLE_ASSISTANT,
                    ) } ?: WorldBookPlacement.DEPTH_ROLE_SYSTEM,
                    groupName = groupName.trim(),
                    sticky = sticky,
                    cooldown = cooldownText.toIntOrNull()?.coerceIn(0, 9999) ?: 0,
                    delay = delayText.toIntOrNull()?.coerceIn(0, 9999) ?: 0,
                )
                showEntryDialog = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            store.save(selected.copy(entries = selected.entries.filterNot { it.id == entry.id } + entry))
                        }
                    }.onSuccess { refresh(selected.id); status = "条目已保存" }
                        .onFailure { status = "保存失败：" + (it.message ?: it::class.java.simpleName) }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { showEntryDialog = false }) { Text("取消") } },
    )

    deleteBook?.let { target -> AlertDialog(
        onDismissRequest = { deleteBook = null },
        title = { Text("删除世界书？") },
        text = { Text("将从本机删除：" + target.name) },
        confirmButton = {
            TextButton(onClick = {
                deleteBook = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { store.delete(target.id) } }
                        .onSuccess {
                            if (activeBookId == target.id) {
                                activeBookId = ""
                                settings.edit().remove("active_worldbook_id").apply()
                            }
                            refresh(null)
                            status = "世界书已删除"
                        }
                        .onFailure { status = "删除失败：" + (it.message ?: it::class.java.simpleName) }
                }
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = { deleteBook = null }) { Text("取消") } },
    ) }
}
