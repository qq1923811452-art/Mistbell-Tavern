package com.mistbell.tavern.v3.feature.worldbook

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Parses standalone SillyTavern World Info JSON without Android or Room dependencies. */
object WorldBookParser {
    fun parse(jsonString: String, fallbackName: String): WorldBook? {
        return try {
            val root = JSONObject(jsonString)
            val rawEntries = root.opt("entries") ?: return null
            val bookId = UUID.randomUUID().toString()
            val name = root.optString("name", "").takeIf { it.isNotBlank() } ?: fallbackName
            val values = when (rawEntries) {
                is JSONObject -> rawEntries.keys().asSequence().mapNotNull { key ->
                    runCatching { key to rawEntries.getJSONObject(key) }.getOrNull()
                }.toList()
                is JSONArray -> (0 until rawEntries.length()).mapNotNull { index ->
                    runCatching { null to rawEntries.getJSONObject(index) }.getOrNull()
                }
                else -> return null
            }
            WorldBook(bookId, name, values.mapNotNull { (mapKey, value) -> parseEntry(value, bookId, mapKey) })
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEntry(value: JSONObject, bookId: String, mapKey: String?): WorldBookEntry? = try {
        val id = value.optString("uid", "").takeIf { it.isNotBlank() }
            ?: value.optString("id", "").takeIf { it.isNotBlank() }
            ?: mapKey?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val enabled = value.optBooleanNullable("enabled")
        val disable = enabled?.not() ?: value.optBooleanNullable("disable") ?: false
        val order = value.optIntNullable("insertion_order") ?: value.optIntNullable("order") ?: 0
        val keys = value.stringList("key")
        val secondaryKeys = value.stringList("keysecondary").ifEmpty { value.stringList("secondary_keys") }
        val extensions = value.optJSONObject("extensions")
        fun rawField(key: String): Any? = extensions?.opt(key)?.takeUnless { it == JSONObject.NULL }
            ?: value.opt(key)?.takeUnless { it == JSONObject.NULL }
        val rawPosition = extensions?.optIntNullable("position") ?: value.optIntNullable("position")
        val stringPosition = value.optString("position", "")
        val depthRaw = extensions?.optIntNullable("depth") ?: value.optIntNullable("depth")
        val position = when {
            rawPosition != null -> mapPosition(rawPosition, depthRaw)
            stringPosition == "after_char" -> WorldBookPlacement.POSITION_AFTER_PROMPT to 0
            else -> WorldBookPlacement.POSITION_BEFORE_PROMPT to 0
        }
        val rawProbability = (rawField("probability") as? Number)?.toDouble()
        val useProbability = rawField("useProbability") as? Boolean
        val probability = when {
            useProbability == false -> 1.0
            rawProbability == null -> 1.0
            rawProbability <= 1.0 -> rawProbability.coerceIn(0.0, 1.0)
            else -> (rawProbability / 100.0).coerceIn(0.0, 1.0)
        }
        val role = when ((rawField("role") as? Number)?.toInt()) {
            1 -> WorldBookPlacement.DEPTH_ROLE_USER
            2 -> WorldBookPlacement.DEPTH_ROLE_ASSISTANT
            else -> WorldBookPlacement.DEPTH_ROLE_SYSTEM
        }
        WorldBookEntry(
            id = id, bookId = bookId, comment = value.optString("comment", ""),
            keys = keys, secondaryKeys = secondaryKeys,
            requireSecondaryMatch = value.optBoolean("selective", false),
            recursive = value.optBoolean("recursive", false),
            content = value.optString("content", ""),
            constant = value.optBoolean("constant", false), disable = disable, order = order,
            insertPosition = position.first, depth = position.second, probability = probability,
            depthRole = role, sticky = value.optBoolean("sticky", false),
            cooldown = value.optInt("cooldown", 0), delay = value.optInt("delay", 0),
            groupName = value.optString("group", ""),
        )
    } catch (_: Exception) {
        null
    }

    private fun mapPosition(position: Int, rawDepth: Int?): Pair<String, Int> = when (position) {
        1 -> WorldBookPlacement.POSITION_AFTER_PROMPT to 0
        2 -> WorldBookPlacement.POSITION_AN_BEFORE to 0
        3 -> WorldBookPlacement.POSITION_AN_AFTER to 0
        4 -> WorldBookPlacement.POSITION_BEFORE_PROMPT to (rawDepth ?: 0).coerceIn(0, 10)
        5 -> WorldBookPlacement.POSITION_EM_BEFORE to 0
        6 -> WorldBookPlacement.POSITION_EM_AFTER to 0
        else -> WorldBookPlacement.POSITION_BEFORE_PROMPT to 0
    }

    private fun JSONObject.stringList(key: String): List<String> = when (val value = opt(key)) {
        is JSONArray -> (0 until value.length()).mapNotNull { index -> value.opt(index) as? String }
        is String -> listOf(value)
        else -> emptyList()
    }

    private fun JSONObject.optBooleanNullable(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else opt(key) as? Boolean

    private fun JSONObject.optIntNullable(key: String): Int? =
        if (!has(key) || isNull(key)) null else opt(key).let { (it as? Number)?.toInt() }
}
