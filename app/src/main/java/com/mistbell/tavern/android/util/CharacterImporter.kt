package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.api.model.CharacterData
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.UUID

data class CharacterImportResult(
    val character: CharacterEntity,
    val worldBook: WorldBookEntity?,
    val worldBookEntries: List<WorldBookEntryEntity>,
    // 导入诊断：非阻断性提示（如未映射字段、v1 老格式等），错误不阻断导入
    val warnings: List<String> = emptyList(),
)

/**
 * 角色卡 JSON 解析纯核心（无 Android 依赖，可直接单元测试）。
 * 支持 SillyTavern 卡片 v2（data/root 双位读取）与 v1 老键名兜底。
 */
object CardParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析卡片 JSON 字符串为导入结果；结构非法返回 null。
     */
    fun parse(jsonString: String): CharacterImportResult? {
        return try {
            val jsonElement = json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject
            val dataObject = jsonObject["data"]?.jsonObject

            // 导入诊断收集（去重、保持插入顺序；错误不阻断导入）
            val warnings = linkedSetOf<String>()

            // v1 老键名来源检测（char_name 等仅存在于 v1 卡片根上）
            val v1Keys = listOf("char_name", "char_persona", "world_scenario", "char_greeting", "example_dialogue")
            if (v1Keys.any { jsonObject.containsKey(it) }) {
                warnings.add("检测到 v1 老格式卡片")
            }

            // 辅助函数：v2 优先 data 位，其次 root 位（v1 卡片字段在根上）
            fun getText(vararg keys: String): String {
                for (key in keys) {
                    dataObject?.get(key)?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotBlank()) return it
                    }
                    jsonObject[key]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotBlank()) return it
                    }
                }
                return ""
            }

            // v1 老键名兜底：char_name/char_persona/world_scenario/char_greeting/example_dialogue/world
            val name = getText("name", "char_name").ifBlank { "未命名角色" }
            val description = getText("description", "desc", "char_persona")
            val personality = getText("personality")
            val scenario = getText("scenario", "world_scenario", "world")
            val firstMes = getText("first_mes", "greeting", "char_greeting")
            val mesExample = getText("mes_example", "example_dialogue", "example")

            // 颜色 / 头像数据（app 私有字段，存在则保留）；裸 base64 头像规范化补 data URI 前缀
            val color = getText("color").ifBlank { "#007AFF" }
            val avatarData = normalizeAvatarData(getText("avatarData", "avatar"))

            // extensions 透传保真：优先 data 位，其次 root 位
            val extensions =
                dataObject?.get("extensions")?.jsonObject
                    ?: jsonObject["extensions"]?.jsonObject
            // extensions 非空时提示已透传的扩展命名空间数量
            if (extensions != null && extensions.isNotEmpty()) {
                warnings.add("已透传 ${extensions.size} 个扩展命名空间")
            }

            // alternate_greetings / tags 提取进 CharacterData
            val alternateGreetings =
                buildList {
                    val arr =
                        dataObject?.get("alternate_greetings")?.jsonArray
                            ?: jsonObject["alternate_greetings"]?.jsonArray
                    arr?.forEach { add(it.jsonPrimitive.contentOrNull ?: "") }
                }
            val tags =
                buildList {
                    val arr =
                        dataObject?.get("tags")?.jsonArray
                            ?: jsonObject["tags"]?.jsonArray
                    arr?.forEach { add(it.jsonPrimitive.contentOrNull ?: "") }
                }

            // v3 卡新增可选字段：多语言备注/来源/资源原样透传保真，读取同 data 位优先、根对象兜底
            val nickname = getText("nickname")
            val creatorNotesMultilingual =
                dataObject?.get("creator_notes_multilingual")
                    ?: jsonObject["creator_notes_multilingual"]
            val source =
                dataObject?.get("source")
                    ?: jsonObject["source"]
            val groupOnlyGreetings =
                buildList {
                    val arr =
                        dataObject?.get("group_only_greetings")?.jsonArray
                            ?: jsonObject["group_only_greetings"]?.jsonArray
                    arr?.forEach { add(it.jsonPrimitive.contentOrNull ?: "") }
                }
            val assets =
                dataObject?.get("assets")
                    ?: jsonObject["assets"]

            // 解析内嵌世界书
            var worldBookEntity: WorldBookEntity? = null
            var worldBookEntries = emptyList<WorldBookEntryEntity>()
            var worldBookId = ""

            // V2 格式：data.character_book；V1 格式：character_book 在根上
            val characterBook =
                dataObject?.get("character_book")?.jsonObject
                    ?: jsonObject["character_book"]?.jsonObject

            if (characterBook != null) {
                worldBookId = UUID.randomUUID().toString()
                val bookName = characterBook["name"]?.jsonPrimitive?.content ?: "$name 的世界书"

                worldBookEntity =
                    WorldBookEntity(
                        id = worldBookId,
                        name = bookName,
                        settingsJson = "{}",
                    )

                worldBookEntries =
                    parseBookEntries(
                        entriesElement = characterBook["entries"],
                        bookId = worldBookId,
                        warnings = warnings,
                    )
            } else {
                // 兼容旧格式
                worldBookId = jsonObject["worldBookId"]?.jsonPrimitive?.content ?: ""
            }

            // 高级字段（CharacterData）
            val systemPrompt = getText("system_prompt")
            val postHistoryInstructions = getText("post_history_instructions")
            val creatorNotes = getText("creator_notes")
            val creator = getText("creator")
            val characterVersion = getText("character_version").ifBlank { "1.0" }

            // 构建 CharacterData（带生态互通字段）
            val characterData =
                CharacterData(
                    systemPrompt = systemPrompt,
                    postHistoryInstructions = postHistoryInstructions,
                    creatorNotes = creatorNotes,
                    creator = creator,
                    characterVersion = characterVersion,
                    alternateGreetings = alternateGreetings,
                    tags = tags,
                    extensions = extensions,
                    nickname = nickname,
                    creatorNotesMultilingual = creatorNotesMultilingual,
                    source = source,
                    groupOnlyGreetings = groupOnlyGreetings,
                    assets = assets,
                )
            val dataJson = json.encodeToString(CharacterData.serializer(), characterData)

            val characterEntity =
                CharacterEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    role = "assistant",
                    description = description,
                    personality = personality,
                    scenario = scenario,
                    firstMes = firstMes,
                    mesExample = mesExample,
                    color = color,
                    avatarData = avatarData,
                    worldBookId = worldBookId,
                    dataJson = dataJson,
                )

            CharacterImportResult(
                character = characterEntity,
                worldBook = worldBookEntity,
                worldBookEntries = worldBookEntries,
                warnings = warnings.toList(),
            )
        } catch (e: Exception) {
            // 纯函数不依赖 android.util.Log（JVM 单测环境不可用）；薄壳层负责日志
            null
        }
    }

    /**
     * 规范化卡片头像字段：已带 data URI 前缀的原样保留；裸 base64 补 png 前缀。
     * 生态部分卡片的 avatar 是文件名/占位符（典型如 Chub 的 "none"）——非 base64 形态一律不当作
     * 头像数据，否则占位符会被包装成非法 data URI、导致 PNG 卡本体头像被跳过（丢图）。
     * （BitmapFactory 按字节 sniff 真实格式，前缀声明与实际格式不一致也能解码。）
     */
    private const val MIN_BASE64_AVATAR_LENGTH = 64

    private fun normalizeAvatarData(raw: String): String =
        when {
            raw.isBlank() -> ""
            raw.startsWith("data:image/") -> raw
            raw.length >= MIN_BASE64_AVATAR_LENGTH && raw.all { it.isBase64PayloadChar() } ->
                "data:image/png;base64,$raw"
            else -> ""
        }

    private fun Char.isBase64PayloadChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/' || this == '='

    /**
     * 解析世界书条目，兼容【数组】与【按 uid 的 map】两种形态。
     * 字段映射规则：enabled 存在时 disable = !enabled；insertion_order 优先于 order；
     * key/keysecondary 单字符串包成数组（keysecondary 暂不映射，收集进 warnings 诊断）；
     * 位置/概率/角色映射规则见 [StInterop.resolvePlacement]（酒馆实测语义）。
     */
    internal fun parseBookEntries(
        entriesElement: kotlinx.serialization.json.JsonElement?,
        bookId: String,
        warnings: MutableSet<String> = mutableSetOf(),
    ): List<WorldBookEntryEntity> {
        if (entriesElement == null) return emptyList()
        val entryObjects: List<Pair<String?, JsonObject>> =
            when (entriesElement) {
                is JsonArray ->
                    entriesElement.mapNotNull { el ->
                        (el as? JsonObject)?.let { null to it }
                    }
                is JsonObject ->
                    entriesElement.map { (uid, el) ->
                        (el as? JsonObject)?.let { uid to it } ?: (uid to JsonObject(emptyMap()))
                    }.filter { it.second.isNotEmpty() }
                else -> return emptyList()
            }
        return entryObjects.mapNotNull { (uid, entry) ->
            try {
                // 条目 id：优先 uid（map 形态的键），其次条目内 id/uid 字段，最后 UUID
                val entryId =
                    uid
                        ?: entry["uid"]?.jsonPrimitive?.contentOrNull
                        ?: entry["id"]?.jsonPrimitive?.contentOrNull
                        ?: UUID.randomUUID().toString()
                val comment = entry["comment"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = entry["content"]?.jsonPrimitive?.contentOrNull ?: ""
                val constant = entry["constant"]?.jsonPrimitive?.booleanOrNull ?: false
                // enabled(布尔)存在时反相为 disable（生态主流字段）；否则退回旧 disable 字段
                val enabled = entry["enabled"]?.jsonPrimitive?.booleanOrNull
                val disable =
                    enabled?.not()
                        ?: entry["disable"]?.jsonPrimitive?.booleanOrNull
                        ?: false
                // insertion_order 优先于 order
                val order =
                    entry["insertion_order"]?.jsonPrimitive?.intOrNull
                        ?: entry["order"]?.jsonPrimitive?.intOrNull
                        ?: 0

                // 位置/概率/@D 角色按酒馆实测语义解析（extensions.* 优先，见 StInterop）
                val placement = StInterop.resolvePlacement(entry, warnings)

                // keys 单字符串包成数组
                val keys =
                    when (val keysElement = entry["key"] ?: entry["keys"]) {
                        is JsonArray -> keysElement.mapNotNull { it.jsonPrimitive.contentOrNull }
                        is JsonPrimitive -> listOfNotNull(keysElement.contentOrNull)
                        else -> emptyList()
                    }
                val secondaryKeys =
                    when (val secondaryElement = entry["keysecondary"] ?: entry["secondary_keys"]) {
                        is JsonArray -> secondaryElement.mapNotNull { it.jsonPrimitive.contentOrNull }
                        is JsonPrimitive -> listOfNotNull(secondaryElement.contentOrNull)
                        else -> emptyList()
                    }
                val keysJson =
                    json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                        keys,
                    )

                WorldBookEntryEntity(
                    id = entryId,
                    bookId = bookId,
                    comment = comment,
                    keysJson = keysJson,
                    content = content,
                    constant = constant,
                    disable = disable,
                    order = order,
                    insertPosition = placement.insertPosition,
                    depth = placement.depth,
                    probability = placement.probability,
                    depthRole = placement.depthRole,
                    secondaryKeysJson =
                        json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                            secondaryKeys,
                        ),
                    sticky = entry["sticky"]?.jsonPrimitive?.booleanOrNull ?: false,
                    cooldown = entry["cooldown"]?.jsonPrimitive?.intOrNull ?: 0,
                    delay = entry["delay"]?.jsonPrimitive?.intOrNull ?: 0,
                    groupName = entry["group"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            } catch (_: Exception) {
                // 纯函数：跳过坏条目（与 ST 生态容错导入约定一致）
                null
            }
        }
    }
}

object CharacterImporter {
    /**
     * PNG 埋卡导入头像的降采样上限：聊天页整屏背景复用 avatarData（1280px），
     * 低于该值会导致背景模糊；高于该值对头像展示无收益且撑大数据库。
     */
    private const val PNG_CARD_AVATAR_MAX_DIM_PX = 1280

    /**
     * 智能导入：自动识别格式（PNG/JSON/CHARX）
     * @param context Android Context
     * @param uri 文件 URI
     * @return CharacterImportResult 或 null（如果解析失败）
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
    ): CharacterImportResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            inputStream.use { stream ->
                // 读取文件头判断格式
                val header = ByteArray(8)
                if (!stream.markSupported()) {
                    // 如果不支持 mark，直接按文件扩展名判断
                    return when {
                        uri.toString().endsWith(".charx", ignoreCase = true) -> importFromCharx(context, uri)
                        uri.toString().endsWith(".png", ignoreCase = true) -> importFromPng(context, uri)
                        else -> importFromJson(context, uri)
                    }
                }

                stream.mark(8)
                val read = stream.read(header)
                stream.reset()

                if (read < 8) {
                    return importFromJson(context, uri)
                }

                when {
                    // PNG 格式（PNG 签名）
                    header.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> {
                        importFromPng(context, uri)
                    }
                    // ZIP 格式（CHARX）：PK 签名
                    header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> {
                        importFromCharx(context, uri)
                    }
                    // JSON 格式
                    else -> {
                        importFromJson(context, uri)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CharacterImporter", "导入失败", e)
            null
        }
    }

    /**
     * 从 CHARX ZIP 文件导入角色和资源
     */
    fun importFromCharx(
        context: Context,
        uri: Uri,
    ): CharacterImportResult? {
        return try {
            val charxResult = CharxImporter.importFromCharx(context, uri) ?: return null

            // 保存 CHARX 资源到本地
            val assetPaths =
                CharxImporter.saveAssets(
                    context,
                    charxResult.character.id,
                    charxResult.assets,
                )

            // 添加资源信息到警告
            val warnings = charxResult.warnings.toMutableList()
            warnings.add("CHARX 格式：已保存 ${assetPaths.size} 个资源文件")

            // CHARX 暂不支持内嵌世界书
            CharacterImportResult(
                character = charxResult.character,
                worldBook = null,
                worldBookEntries = emptyList(),
                warnings = warnings,
            )
        } catch (e: Exception) {
            android.util.Log.e("CharacterImporter", "CHARX 导入失败", e)
            null
        }
    }

    /**
     * 从 JSON 文件导入角色（包括可能的世界书）
     * @param context Android Context
     * @param uri 文件 URI
     * @return CharacterImportResult 或 null（如果解析失败）
     */
    fun importFromJson(
        context: Context,
        uri: Uri,
    ): CharacterImportResult? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val jsonString = inputStream?.use { BoundedRead.text(it, label = "角色卡 JSON ") } ?: return null
            CardParser.parse(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("CharacterImporter", "读取 JSON 文件失败", e)
            null
        }
    }

    /**
     * 从 PNG 埋卡导入角色：读取埋卡 chunk（tEXt/zTXt，chara/ccv3 关键字）→ 解析卡片 JSON。
     * v2/v3 卡 chunk 值为 base64(JSON)；v1 卡为裸 JSON——base64 解码失败按裸 JSON 兜底。
     * 无埋卡（或非 PNG）返回 null。
     * 头像语义（生态约定）：PNG 卡的图像本体即权威头像——能提取则一律覆盖 JSON 内嵌值
     * （JSON 的 avatar 字段常是 "none" 等占位符）；仅提取失败时才保留 JSON 内嵌头像并提示。
     */
    fun importFromPng(
        context: Context,
        uri: Uri,
    ): CharacterImportResult? {
        return try {
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { BoundedRead.bytes(it, label = "角色卡 PNG ") }
                    ?: return null
            val chunkText = PngCard.readTextChunk(bytes) ?: return null
            // v1 卡兜底：chunk 值不是 base64 但以 { 开头时按裸 JSON 处理
            val jsonString =
                PngCard.decodeCardJson(chunkText)
                    ?: chunkText.trimStart().takeIf { it.startsWith("{") }
                    ?: return null
            val result = CardParser.parse(jsonString) ?: return null
            val pngAvatar = ImageUtils.bytesToAvatarDataUri(bytes, PNG_CARD_AVATAR_MAX_DIM_PX)
            when {
                pngAvatar != null ->
                    result.copy(character = result.character.copy(avatarData = pngAvatar))
                result.character.avatarData.isBlank() ->
                    result.copy(warnings = result.warnings + "头像提取失败，角色已导入但无头像")
                else ->
                    result.copy(warnings = result.warnings + "PNG 头像提取失败，已保留卡片内嵌头像")
            }
        } catch (e: Exception) {
            android.util.Log.e("CharacterImporter", "读取 PNG 埋卡失败", e)
            null
        }
    }
}
