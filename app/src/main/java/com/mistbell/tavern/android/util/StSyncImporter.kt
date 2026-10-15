package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 「从酒馆同步」——单向文件同步（桌面酒馆 → 本应用）。
 *
 * 用户把酒馆数据目录下的 characters 文件夹（.png 埋卡，V2/V3）与 worlds 文件夹
 * （.json 独立世界书）拷到手机后，用系统文件夹选择器选中任一父目录，
 * 本文件递归扫描并批量导入/更新。
 *
 * 同步语义（与单次导入的本质区别）：**幂等**——按 trim 后的名字做身份匹配，
 * 已存在则按 PC 版覆盖更新并保留本地 id（聊天记录不断链），重复执行不产生重复数据；
 * 批内同名先到先得；不做删除（文件夹里没有的本地数据不动）。
 *
 * 结构对齐 CharacterImporter.kt：纯决策核心（无 Android 依赖，可单测）+ 薄 Android 壳。
 *
 * 同步结果报告（明细列表全量携带，展示层负责截断）
 */
data class SyncReport(
    val createdCharacters: Int,
    val updatedCharacters: Int,
    val createdBooks: Int,
    val updatedBooks: Int,
    // 无埋卡的普通 PNG（立绘/表情差分），聚合计数不逐条列出
    val skippedSprites: Int,
    // "相对路径：原因"
    val skippedFiles: List<String>,
    // "相对路径：异常摘要"
    val failedFiles: List<String>,
    // 批内同名被先到先得挡下的文件
    val duplicateNames: List<String>,
    val durationMs: Long,
)

/** JSON 文件分类：决定走世界书导入、角色卡导入还是跳过（纯函数） */
object StSyncClassifier {
    sealed interface Kind {
        data object WorldBook : Kind

        data object Card : Kind

        data class Unknown(val reason: String) : Kind
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun classifyJson(jsonString: String): Kind {
        val root =
            try {
                json.parseToJsonElement(jsonString)
            } catch (_: Exception) {
                return Kind.Unknown("JSON 解析失败")
            }
        val obj = root as? JsonObject ?: return Kind.Unknown("非 JSON 对象")
        // 世界书先判：根含 entries（uid-map 与数组两种形态均认）——
        // 必须在卡解析之前判，否则 CardParser 会把世界书 JSON 解析成"未命名角色"空卡
        if (obj["entries"] is JsonObject || obj["entries"] is JsonArray) return Kind.WorldBook
        // 卡片签名：v2/v3 的 spec/data、v1 老键名、或 name+任一定义字段
        val hasCardSignature =
            obj.containsKey("spec") ||
                obj.containsKey("data") ||
                obj.containsKey("char_name") ||
                obj.containsKey("char_persona") ||
                obj.containsKey("char_greeting") ||
                (
                    obj.containsKey("name") &&
                        (obj.containsKey("description") || obj.containsKey("first_mes") || obj.containsKey("personality"))
                )
        return if (hasCardSignature) Kind.Card else Kind.Unknown("无法识别的 JSON 结构")
    }
}

/** 同步期身份登记簿："trim 后名字 → id"，先到先得（DB 既有 + 本批新建共用） */
class SyncNameIndex(initial: List<Pair<String, String>> = emptyList()) {
    private val byName = LinkedHashMap<String, String>()

    init {
        initial.forEach { (name, id) -> byName.putIfAbsent(name.trim(), id) }
    }

    /** 查询身份 id（trim 后精确匹配），无则 null */
    fun lookup(name: String): String? = byName[name.trim()]

    /** 先到先得登记：已有同名时保留先到的 id */
    fun register(
        name: String,
        id: String,
    ) {
        byName.putIfAbsent(name.trim(), id)
    }

    /** 名字 → id 的只读视图（卡→书链接解析用） */
    fun asMap(): Map<String, String> = byName
}

/**
 * 已存在角色的同步合并：保留 id（聊天记录不断链）与本地个性化（role/color/themeId），
 * 其余按 PC 版覆盖；头像仅在新值非空时覆盖——瞬时解码失败不能抹掉已有头像。
 */
fun applyCardToExisting(
    existing: CharacterEntity,
    parsed: CharacterImportResult,
    worldBookId: String,
): CharacterEntity =
    existing.copy(
        name = parsed.character.name,
        description = parsed.character.description,
        personality = parsed.character.personality,
        scenario = parsed.character.scenario,
        firstMes = parsed.character.firstMes,
        mesExample = parsed.character.mesExample,
        avatarData = parsed.character.avatarData.ifBlank { existing.avatarData },
        worldBookId = worldBookId,
        dataJson = parsed.character.dataJson,
    )

/**
 * 卡 → 世界书链接解析：内嵌书名优先，其次 data.extensions.world；两路都查不到则不链接。
 * booksByName 是"书名 → 稳定书 id"映射（DB 既有 + 本批新建）。
 */
fun resolveWorldBookId(
    embeddedBookName: String?,
    extensionsWorld: String?,
    booksByName: Map<String, String>,
): String =
    when {
        embeddedBookName != null -> booksByName[embeddedBookName.trim()].orEmpty()
        !extensionsWorld.isNullOrBlank() -> booksByName[extensionsWorld.trim()].orEmpty()
        else -> ""
    }

/** 从解析结果 dataJson 的 extensions 透传里读出 world 书名（酒馆卡→书链接字段） */
fun extensionsWorldName(parsed: CharacterImportResult): String? =
    try {
        val data =
            Json { ignoreUnknownKeys = true }
                .decodeFromString(CharacterData.serializer(), parsed.character.dataJson)
        (data.extensions?.get("world") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

object StSyncImporter {
    private const val TAG = "StSync"

    /** 目录递归深度上限：防异常嵌套拖死遍历 */
    private const val MAX_SCAN_DEPTH = 15

    /**
     * 单次批量导入的文件数量上限。
     *
     * 只限深度挡不住「一个扁平目录里塞几十万个文件」——那会让遍历与后续逐文件读取
     * 长时间占满 IO。超过上限时**中止**而不是静默只导入一部分：部分导入对用户是
     * 「看起来成功、实际缺数据」，比明确失败更糟。调用方已用 try/catch 显示失败原因。
     */
    private const val MAX_SCAN_FILES = 5_000

    /**
     * 单次扫描**遍历过的节点**上限（含目录与不匹配扩展名的文件）。
     *
     * 与 [MAX_SCAN_FILES] 的区别很重要：后者只在发现 `.png`/`.json` 时递增，
     * 因此一棵塞满其它扩展名文件或大量子目录的树，可以在「可导入文件数」远未到限的情况下
     * 持续执行游标查询与递归 —— 遍历工作量必须单独设限。
     */
    private const val MAX_SCAN_NODES = 50_000

    /** 扫描期单文件记录（relPath 用于报告展示与同名排序，不含文件内容） */
    private data class ScannedFile(
        val uri: Uri,
        val relPath: String,
        val displayName: String,
    ) {
        val isPng: Boolean get() = displayName.lowercase().endsWith(".png")
        val isJson: Boolean get() = displayName.lowercase().endsWith(".json")
    }

    /**
     * 扫描文件夹并同步。全程每文件独立 try/catch——单文件失败不中断批次。
     * 顺序：阶段一世界书先行（角色可能要按 extensions.world 链接它们），阶段二角色。
     */
    suspend fun syncFromFolder(
        context: Context,
        treeUri: Uri,
        db: AppDatabase,
    ): SyncReport {
        val startedAt = System.currentTimeMillis()
        val skippedFiles = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()
        val duplicateNames = mutableListOf<String>()
        var skippedSprites = 0
        var createdCharacters = 0
        var updatedCharacters = 0
        var createdBooks = 0
        var updatedBooks = 0

        val files = scanTree(context, treeUri)

        // —— 既有数据登记：按 (trim 名字, id) 排序，重复名时"先到先得"跨运行确定 ——
        val dbBooks =
            db.worldBookDao().getAllBooksOnce().sortedWith(compareBy({ it.name.trim() }, { it.id }))
        val bookIndex = SyncNameIndex(dbBooks.map { it.name.trim() to it.id })
        val bookEntityByName = LinkedHashMap<String, WorldBookEntity>()
        dbBooks.forEach { bookEntityByName.putIfAbsent(it.name.trim(), it) }

        val dbCharacters =
            db.characterDao().getAllOnce().sortedWith(compareBy({ it.name.trim() }, { it.id }))
        val charIndex = SyncNameIndex(dbCharacters.map { it.name.trim() to it.id })
        val charEntityByName = LinkedHashMap<String, CharacterEntity>()
        dbCharacters.forEach { charEntityByName.putIfAbsent(it.name.trim(), it) }

        // 批内先到先得守卫：同批第二次出现同名即记重复、不再导入（防止互相覆盖出不确定结果）
        val batchBookNames = mutableSetOf<String>()
        val batchCharNames = mutableSetOf<String>()

        /** 按书名同步一本书：新→建；已有→保 id 保 settingsJson、删光条目重插。返回稳定书 id */
        suspend fun upsertBookByName(
            name: String,
            book: WorldBookEntity,
            entries: List<WorldBookEntryEntity>,
        ): String {
            val existing = bookEntityByName[name]
            if (existing == null) {
                db.withTransaction {
                    db.worldBookDao().upsertBook(book)
                    if (entries.isNotEmpty()) db.worldBookDao().upsertEntries(entries)
                }
                bookEntityByName[name] = book
                bookIndex.register(name, book.id)
                createdBooks++
                return book.id
            }
            // 注意不能碰 WorldBookDao.replaceAll——那是整表清空（备份专用）
            db.withTransaction {
                db.worldBookDao().upsertBook(existing.copy(name = name))
                db.worldBookDao().deleteEntriesByBookId(existing.id)
                if (entries.isNotEmpty()) {
                    db.worldBookDao().upsertEntries(entries.map { it.copy(bookId = existing.id) })
                }
            }
            updatedBooks++
            return existing.id
        }

        // —— 阶段一：世界书先行；卡片 JSON 只登记、内容延后到阶段二重读（内存持平）——
        val pendingCards = mutableListOf<ScannedFile>()
        files.filter { it.isJson }.forEach { file ->
            try {
                val text = readText(context, file.uri)
                if (text == null) {
                    skippedFiles += "${file.relPath}：无法读取"
                    return@forEach
                }
                when (val kind = StSyncClassifier.classifyJson(text)) {
                    is StSyncClassifier.Kind.WorldBook -> {
                        val fallbackName = file.displayName.substringBeforeLast('.').trim()
                        val parsed = WorldBookParser.parse(text, fallbackName)
                        if (parsed == null) {
                            skippedFiles += "${file.relPath}：世界书解析失败"
                            return@forEach
                        }
                        val (book, entries) = parsed
                        val name = book.name.trim().ifBlank { fallbackName }
                        if (!batchBookNames.add(name)) {
                            duplicateNames += "${file.relPath}（世界书「$name」同名，先到先得）"
                            return@forEach
                        }
                        upsertBookByName(name, book.copy(name = name), entries)
                    }
                    is StSyncClassifier.Kind.Card -> pendingCards += file
                    is StSyncClassifier.Kind.Unknown -> skippedFiles += "${file.relPath}：${kind.reason}"
                }
            } catch (e: Exception) {
                failedFiles += "${file.relPath}：${e.message ?: e.javaClass.simpleName}"
            }
        }

        // —— 阶段二：角色（PNG 埋卡 + 阶段一分类出的卡片 JSON）——
        (files.filter { it.isPng } + pendingCards).forEach { file ->
            try {
                val parsed: CharacterImportResult? =
                    if (file.isPng) {
                        // 无埋卡的普通 PNG（立绘/表情差分）返回 null，聚合计数
                        CharacterImporter.importFromPng(context, file.uri)
                    } else {
                        readText(context, file.uri)?.let { CardParser.parse(it) }
                    }
                if (parsed == null) {
                    if (file.isPng) skippedSprites++ else skippedFiles += "${file.relPath}：角色卡解析失败"
                    return@forEach
                }
                val charName = parsed.character.name.trim()
                if (!batchCharNames.add(charName)) {
                    duplicateNames += "${file.relPath}（角色「$charName」同名，先到先得）"
                    return@forEach
                }

                // 卡→书链接：内嵌书先按书名同步（同批重复只链接不覆盖），再退 extensions.world
                var embeddedBookName: String? = null
                parsed.worldBook?.let { embedded ->
                    val bookName = embedded.name.trim().ifBlank { "$charName 的世界书" }
                    if (batchBookNames.add(bookName)) {
                        upsertBookByName(bookName, embedded.copy(name = bookName), parsed.worldBookEntries)
                    } else {
                        duplicateNames += "${file.relPath}（世界书「$bookName」同名，先到先得）"
                    }
                    embeddedBookName = bookName
                }
                val worldBookId =
                    resolveWorldBookId(
                        embeddedBookName = embeddedBookName,
                        extensionsWorld = extensionsWorldName(parsed),
                        booksByName = bookIndex.asMap(),
                    )

                val existing = charEntityByName[charName]
                if (existing == null) {
                    db.characterDao().upsert(parsed.character.copy(worldBookId = worldBookId))
                    charIndex.register(charName, parsed.character.id)
                    createdCharacters++
                } else {
                    db.characterDao().upsert(applyCardToExisting(existing, parsed, worldBookId))
                    updatedCharacters++
                }
            } catch (e: Exception) {
                failedFiles += "${file.relPath}：${e.message ?: e.javaClass.simpleName}"
            }
        }

        val report =
            SyncReport(
                createdCharacters = createdCharacters,
                updatedCharacters = updatedCharacters,
                createdBooks = createdBooks,
                updatedBooks = updatedBooks,
                skippedSprites = skippedSprites,
                skippedFiles = skippedFiles.toList(),
                failedFiles = failedFiles.toList(),
                duplicateNames = duplicateNames.toList(),
                durationMs = System.currentTimeMillis() - startedAt,
            )
        // 只记计数与文件路径，不落卡片正文（隐私约定同 importCharacter）
        Log.i(
            TAG,
            "同步完成：角色 +${report.createdCharacters}/↻${report.updatedCharacters}，" +
                "世界书 +${report.createdBooks}/↻${report.updatedBooks}，跳过图片 ${report.skippedSprites}，" +
                "跳过 ${report.skippedFiles.size}，失败 ${report.failedFiles.size}，" +
                "重复 ${report.duplicateNames.size}，${report.durationMs}ms",
        )
        return report
    }

    /** 读文档树文件为 UTF-8 文本（去 BOM）；打不开或超过大小上限返回 null（由解析失败兜住，不致命） */
    private fun readText(
        context: Context,
        uri: Uri,
    ): String? =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Let size-limit/read failures reach the per-file catch so SyncReport explains the failure.
            BoundedRead.text(stream, label = "文档树文件 ")
        }?.removePrefix("\uFEFF")

    /**
     * 递归扫描文档树（裸 DocumentsContract，每目录一次游标查询——无 documentfile 依赖、无逐文件查询）。
     * 只收 .png/.json；结果按相对路径排序——同名"先到先得"跨运行确定。
     */
    private fun scanTree(
        context: Context,
        treeUri: Uri,
    ): List<ScannedFile> {
        val collected = mutableListOf<ScannedFile>()
        var visitedNodes = 0

        fun walk(
            parentDocumentId: String,
            relDir: String,
            depth: Int,
        ) {
            if (depth > MAX_SCAN_DEPTH) return
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    // 这里统计**遍历过的节点**（含目录与不匹配扩展名的文件），
                    // 与 MAX_SCAN_FILES 区分开：后者只统计会真正被导入的文件。
                    // 只限后者的话，一棵塞满非 .png/.json 的目录树仍可无界遍历。
                    visitedNodes++
                    require(visitedNodes <= MAX_SCAN_NODES) {
                        "文档树节点数超过 $MAX_SCAN_NODES 个上限，已中止批量导入；请选择更小的目录"
                    }
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(documentId, if (relDir.isEmpty()) displayName else "$relDir/$displayName", depth + 1)
                    } else {
                        val lower = displayName.lowercase()
                        if (lower.endsWith(".png") || lower.endsWith(".json")) {
                            require(collected.size < MAX_SCAN_FILES) {
                                "文档树中可导入的文件超过 $MAX_SCAN_FILES 个上限，已中止批量导入；请选择更小的目录"
                            }
                            collected +=
                                ScannedFile(
                                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                    relPath = if (relDir.isEmpty()) displayName else "$relDir/$displayName",
                                    displayName = displayName,
                                )
                        }
                    }
                }
            }
        }

        walk(DocumentsContract.getTreeDocumentId(treeUri), "", 0)
        return collected.sortedBy { it.relPath }
    }
}
