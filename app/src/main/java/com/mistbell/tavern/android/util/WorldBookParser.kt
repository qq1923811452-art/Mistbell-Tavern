package com.mistbell.tavern.android.util

import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * SillyTavern 独立世界书（World Info / Lorebook）JSON 解析器（纯函数，无 Context）。
 *
 * ST 独立世界书文件的主流形态：
 *   { "name": "...", "entries": { "<uid>": { ... } } }   —— entries 是按 uid 的 map
 * 也兼容 entries 为数组（与卡内嵌 character_book 相同的形态）。
 *
 * 条目字段映射规则（与 docs/FOUNDATION.md「关键互通格式要点」一致）：
 *   - enabled（布尔）存在时 disable = !enabled（生态主流字段；只读 disable 是旧 bug）
 *   - insertion_order 优先于 order
 *   - key / keysecondary 单字符串规整成数组（keysecondary 当前不映射，待后续支持次级关键词）
 *   - 条目 id 用 uid（缺失则生成 UUID）
 *   - position 为酒馆数字枚举（0-7，4=atDepth 配 depth/role），概率为 0-100 百分数——
 *     映射规则与卡内嵌书共用 [StInterop.resolvePlacement]（酒馆实测语义）
 */
object WorldBookParser {
    /**
     * 解析独立世界书 JSON。
     * @param jsonString 文件全文
     * @param fallbackName 书名兜底（通常用文件名去后缀）
     * @return (书实体, 条目列表)，解析失败返回 null
     */
    fun parse(
        jsonString: String,
        fallbackName: String,
    ): Pair<WorldBookEntity, List<WorldBookEntryEntity>>? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(jsonString).jsonObject

            val entriesElement = root["entries"] ?: return null
            val bookId = UUID.randomUUID().toString()
            // 书名取根对象 name，缺失用 fallbackName
            val bookName =
                root["name"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull
                }?.takeIf { it.isNotBlank() } ?: fallbackName

            val entries: List<Pair<String?, JsonObject>> =
                when (entriesElement) {
                    // 主流形态：按 uid 的 map——map 键就是条目 uid（兜底），需要传给条目解析
                    is JsonObject ->
                        entriesElement.entries.mapNotNull { (key, el) ->
                            try {
                                key to el.jsonObject
                            } catch (_: Exception) {
                                null
                            }
                        }
                    // 兼容形态：数组（卡内嵌结构相同，无 map 键）
                    is JsonArray ->
                        entriesElement.mapNotNull { el ->
                            try {
                                null to el.jsonObject
                            } catch (_: Exception) {
                                null
                            }
                        }
                    else -> return null
                }

            val book =
                WorldBookEntity(
                    id = bookId,
                    name = bookName,
                    settingsJson = "{}",
                )

            val entryEntities = entries.mapNotNull { (mapKey, entry) -> parseEntry(entry, bookId, mapKey) }

            book to entryEntities
        } catch (_: Exception) {
            null
        }
    }

    /** 单个条目 → 实体；mapKey 为 uid-map 形态的键（条目无 uid/id 字段时的兜底 id）；解析失败返回 null */
    private fun parseEntry(
        entry: JsonObject,
        bookId: String,
        mapKey: String? = null,
    ): WorldBookEntryEntity? {
        return try {
            // 条目 id 优先级：uid 字段 > id 字段 > uid-map 的键 > UUID
            val entryId =
                entry["uid"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotBlank() }
                    ?: entry["id"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }
                    ?: mapKey?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()

            val comment = entry["comment"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val content = entry["content"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val constant = entry["constant"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false

            // enabled 存在时反相为 disable（生态主流字段）；否则读旧字段 disable
            val disable =
                when (val enabledEl = entry["enabled"]) {
                    is JsonPrimitive ->
                        enabledEl.booleanOrNull?.not()
                            ?: entry["disable"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                    else -> entry["disable"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                }

            // insertion_order 优先于 order
            val order =
                entry["insertion_order"]?.let { (it as? JsonPrimitive)?.intOrNull }
                    ?: entry["order"]?.let { (it as? JsonPrimitive)?.intOrNull }
                    ?: 0

            // key / keysecondary 单字符串规整成数组
            val keys = parseKeys(entry["key"])
            val secondaryKeys = parseKeys(entry["keysecondary"] ?: entry["secondary_keys"])
            val keysJson =
                Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                    keys,
                )

            // 位置/概率/@D 角色按酒馆实测语义解析（与卡内嵌书同一映射；诊断提示此处不展示，丢弃）
            val placement = StInterop.resolvePlacement(entry, mutableSetOf())

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
                    Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                        secondaryKeys,
                    ),
                sticky = entry["sticky"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false,
                cooldown = entry["cooldown"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0,
                delay = entry["delay"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0,
                groupName = entry["group"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** key 字段：数组直接取，单字符串包成数组，缺失/异常返回空列表 */
    private fun parseKeys(element: kotlinx.serialization.json.JsonElement?): List<String> {
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }
    }
}
