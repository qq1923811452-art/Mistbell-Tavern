package com.mistbell.tavern.v3.feature.character

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.ui.CharacterAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_CHARACTER_CARD_BYTES = 5 * 1024 * 1024
private const val MAX_CHARACTER_ART_BYTES = 8 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "ThrowsCount", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
fun CharacterCardLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { CharacterCardLibraryStore(File(context.filesDir, "characters")) }
    val artStore = remember(context) { CharacterArtStore(CharacterArtStore.directoryOf(context.filesDir)) }
    var cards by remember { mutableStateOf(emptyList<StoredCharacterCard>()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<StoredCharacterCard?>(null) }
    var exportTarget by remember { mutableStateOf<StoredCharacterCard?>(null) }
    var status by remember { mutableStateOf("角色卡保存在应用私有目录；导入/导出使用 SillyTavern JSON 格式。") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var scenario by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var exampleDialogue by remember { mutableStateOf("") }
    var creatorNotes by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var postHistoryInstructions by remember { mutableStateOf("") }
    var alternateGreetingsText by remember { mutableStateOf("") }
    var characterBookJson by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.list() } }
                .onSuccess { cards = it }
                .onFailure { status = "读取角色卡失败：${it.message ?: it::class.java.simpleName}" }
        }
    }

    fun beginEdit(item: StoredCharacterCard?) {
        editingId = item?.id
        val card = item?.card
        name = card?.name.orEmpty()
        description = card?.description.orEmpty()
        personality = card?.personality.orEmpty()
        scenario = card?.scenario.orEmpty()
        firstMessage = card?.firstMessage.orEmpty()
        exampleDialogue = card?.exampleDialogue.orEmpty()
        creatorNotes = card?.creatorNotes.orEmpty()
        systemPrompt = card?.systemPrompt.orEmpty()
        postHistoryInstructions = card?.postHistoryInstructions.orEmpty()
        alternateGreetingsText = card?.alternateGreetings.orEmpty().joinToString("\n")
        characterBookJson = card?.characterBookJson.orEmpty()
        tagsText = card?.tags.orEmpty().joinToString(", ")
        creator = card?.creator.orEmpty()
        showEditor = true
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching {
                        val raw =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        require(output.size() + count <= MAX_CHARACTER_CARD_BYTES) {
                                            "角色卡 JSON 超过 5 MiB 限制。"
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray().toString(Charsets.UTF_8)
                                } ?: throw IllegalStateException("无法读取所选文件。")
                            }
                        val card = LegacyCharacterCardImporter.import(raw)
                        withContext(Dispatchers.IO) { store.save(null, card) }
                        card
                    }.onSuccess {
                        refresh()
                        CharacterLibraryRevision.bump()
                        status = "已导入角色：${it.name}"
                    }.onFailure {
                        status = "导入失败：${it.message ?: it::class.java.simpleName}"
                    }
                }
            }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val target = exportTarget
            exportTarget = null
            if (uri != null && target != null) {
                scope.launch {
                    runCatching {
                        val json = CharacterCardJsonCodec.export(target.card)
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)
                                ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                                ?: error("无法写入所选导出位置。")
                        }
                    }.onSuccess { status = "已导出角色：${target.card.name}" }
                        .onFailure { status = "导出失败：${it.message ?: it::class.java.simpleName}" }
                }
            }
        }

    var artTarget by remember { mutableStateOf<Pair<StoredCharacterCard, CharacterArtKind>?>(null) }
    val artLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = artTarget
            artTarget = null
            if (uri != null && target != null) {
                scope.launch {
                    runCatching {
                        val bytes =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        require(output.size() + count <= MAX_CHARACTER_ART_BYTES) { "图片超过 8 MiB 限制。" }
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                } ?: error("无法读取所选图片。")
                            }
                        val (item, kind) = target
                        val fileName = withContext(Dispatchers.IO) { artStore.save(kind, item.card.name, bytes) }
                        val updated =
                            when (kind) {
                                CharacterArtKind.Avatar -> item.card.copy(avatarFileName = fileName)
                                CharacterArtKind.Portrait -> item.card.copy(portraitFileName = fileName)
                            }
                        withContext(Dispatchers.IO) { store.save(item.id, updated) }
                    }.onSuccess {
                        refresh()
                        CharacterLibraryRevision.bump()
                        status = "已更新角色形象：${target.first.card.name}"
                    }.onFailure {
                        status = "设置形象失败：${it.message ?: it::class.java.simpleName}"
                    }
                }
            }
        }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showEditor) "编辑角色卡" else "角色管理") },
                navigationIcon = {
                    TextButton(onClick = { if (showEditor) showEditor = false else onBack() }) { Text("返回") }
                },
            )
        },
    ) { padding ->
        if (showEditor) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("基础信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val editorCard = cards.firstOrNull { it.id == editingId }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CharacterAvatar(
                            name = name.ifBlank { "?" },
                            avatarFileName = editorCard?.card?.avatarFileName,
                            size = 56.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (editorCard?.card?.portraitFileName == null) "立绘未设置" else "立绘已设置",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    enabled = editorCard != null,
                                    onClick = {
                                        if (editorCard != null) {
                                            artTarget = editorCard to CharacterArtKind.Avatar
                                            artLauncher.launch(arrayOf("image/*"))
                                        }
                                    },
                                ) { Text("设置头像") }
                                TextButton(
                                    enabled = editorCard != null,
                                    onClick = {
                                        if (editorCard != null) {
                                            artTarget = editorCard to CharacterArtKind.Portrait
                                            artLauncher.launch(arrayOf("image/*"))
                                        }
                                    },
                                ) { Text("设置立绘") }
                            }
                            if (editorCard == null) {
                                Text(
                                    text = "先保存一次，再设置头像与立绘",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("角色名称 *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        description,
                        { description = it },
                        label = { Text("描述") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    OutlinedTextField(
                        personality,
                        { personality = it },
                        label = { Text("性格") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    OutlinedTextField(scenario, { scenario = it }, label = { Text("场景") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(
                        firstMessage,
                        { firstMessage = it },
                        label = { Text("开场白") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    OutlinedTextField(
                        exampleDialogue,
                        { exampleDialogue = it },
                        label = { Text("示例对话") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    OutlinedTextField(
                        creatorNotes,
                        { creatorNotes = it },
                        label = { Text("创作者备注") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    OutlinedTextField(
                        systemPrompt,
                        { systemPrompt = it },
                        label = { Text("系统提示词") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    OutlinedTextField(postHistoryInstructions, {
                        postHistoryInstructions = it
                    }, label = { Text("历史后置指令") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(alternateGreetingsText, {
                        alternateGreetingsText = it
                    }, label = { Text("备用开场白（每行一条）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(characterBookJson, {
                        characterBookJson = it
                    }, label = { Text("角色内嵌世界书 JSON（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(tagsText, { tagsText = it }, label = { Text("标签（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        creator,
                        { creator = it },
                        label = { Text("创作者") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val card =
                                        CharacterCard(
                                            name = name.trim(),
                                            description = description,
                                            personality = personality,
                                            scenario = scenario,
                                            firstMessage = firstMessage,
                                            exampleDialogue = exampleDialogue,
                                            creatorNotes = creatorNotes,
                                            systemPrompt = systemPrompt,
                                            postHistoryInstructions = postHistoryInstructions,
                                            alternateGreetings =
                                                alternateGreetingsText.lines().map { it.trim() }.filter(
                                                    String::isNotEmpty,
                                                ),
                                            tags = tagsText.split(',').map { it.trim() }.filter(String::isNotEmpty),
                                            creator = creator,
                                            characterBookJson = characterBookJson.trim().takeIf(String::isNotEmpty),
                                            avatarFileName =
                                                cards.firstOrNull { it.id == editingId }?.card?.avatarFileName,
                                            portraitFileName =
                                                cards.firstOrNull { it.id == editingId }?.card?.portraitFileName,
                                        )
                                    require(card.name.isNotBlank()) { "角色名称不能为空。" }
                                    withContext(Dispatchers.IO) { store.save(editingId, card) }
                                    card
                                }.onSuccess {
                                    showEditor = false
                                    refresh()
                                    CharacterLibraryRevision.bump()
                                    status = "已保存角色：${it.name}"
                                }.onFailure {
                                    status = "保存失败：${it.message ?: it::class.java.simpleName}"
                                }
                            }
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存角色卡") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { beginEdit(null) }, modifier = Modifier.weight(1f)) { Text("新建角色") }
                        Button(onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
                        }, modifier = Modifier.weight(1f)) { Text("导入 JSON") }
                    }
                }
                if (cards.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("还没有角色卡", style = MaterialTheme.typography.titleMedium)
                                Text("可以新建角色，或导入已有的 SillyTavern JSON 角色卡。", modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                items(cards, key = StoredCharacterCard::id) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CharacterAvatar(
                                    name = item.card.name,
                                    avatarFileName = item.card.avatarFileName,
                                    size = 52.dp,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = if (item.card.portraitFileName == null) "未设置立绘" else "已设置立绘",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (item.card.description.isNotBlank()) Text(item.card.description, maxLines = 3)
                            if (item.card.tags.isNotEmpty()) {
                                Text(item.card.tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    artTarget = item to CharacterArtKind.Avatar
                                    artLauncher.launch(arrayOf("image/*"))
                                }) { Text("头像") }
                                TextButton(onClick = {
                                    artTarget = item to CharacterArtKind.Portrait
                                    artLauncher.launch(arrayOf("image/*"))
                                }) { Text("立绘") }
                                TextButton(onClick = { beginEdit(item) }) { Text("编辑") }
                                TextButton(onClick = {
                                    exportTarget = item
                                    exportLauncher.launch("${item.card.name}.json")
                                }) { Text("导出") }
                                TextButton(onClick = { deleteTarget = item }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除角色卡？") },
            text = { Text("将从本机角色库删除“${target.card.name}”。此操作不会删除已导出的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.delete(target.id) } }
                            .onSuccess {
                                refresh()
                                CharacterLibraryRevision.bump()
                                status = "已删除角色：${target.card.name}"
                            }
                            .onFailure { status = "删除失败：${it.message ?: it::class.java.simpleName}" }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}
