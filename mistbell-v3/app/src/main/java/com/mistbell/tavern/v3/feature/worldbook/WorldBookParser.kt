package com.mistbell.tavern.v3.feature.worldbook

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Parses standalone SillyTavern World Info JSON without Android or Room dependencies. */
object WorldBookParser {

    private data class Placement(val position: String, val depth: Int)

    fun parse(jsonString: String, fallbackName: String): WorldBook? = runCatching {
        val root = JSONObject(jsonString)
        val rawEntries = root.opt("entries") ?: return null
        val name = root.optString("name").takeIf(String::isNotBlank) ?: fallbackName
        val bookId = root.optString("id").takeIf { UUID_PATTERN.matches(it) } ?: UUID.randomUUID().toString()
        WorldBook(bookId, name, collectEntries(rawEntries, bookId))
    }.getOrNull()

    private fun collectEntries(raw: Any, bookId: String): List<WorldBookEntry> = when (raw) {
        is JSONObject -> raw.keys().asSequence()
            .mapNotNull { key -> runCatching { key to raw.getJSONObject(key) }.getOrNull() }
            .mapNotNull { (key, node) -> parseEntry(node, bookId, key) }
            .toList()

        is JSONArray -> (0 until raw.length())
            .mapNotNull { index -> runCatching { raw.getJSONObject(index) }.getOrNull() }
            .mapNotNull { node -> parseEntry(node, bookId, null) }
            .toList()

        else -> emptyList()
    }

    private fun parseEntry(node: JSONObject, bookId: String, mapKey: String?): WorldBookEntry? = runCatching {
        val extensions = node.optJSONObject("extensions")
        val placement = placementOf(node, extensions)
        WorldBookEntry(
            id = entryId(node, mapKey),
            bookId = bookId,
            comment = node.optString("comment"),
            keys = keywordList(node.opt("key")).ifEmpty { keywordList(node.opt("keys")) },
            secondaryKeys = keywordList(node.opt("keysecondary")).ifEmpty {
                keywordList(node.opt("secondary_keys"))
            },
            requireSecondaryMatch = node.optBoolean("selective", false),
            recursive = node.optBoolean("recursive", false),
            content = node.optString("content"),
            constant = node.optBoolean("constant", false),
            disable = (field(node, extensions, "enabled") as? Boolean)?.not()
                ?: (field(node, extensions, "disable") as? Boolean)
                ?: false,
            order = nullableInt(node, extensions, "insertion_order")
                ?: nullableInt(node, extensions, "order")
                ?: 0,
            insertPosition = placement.position,
            depth = placement.depth,
            probability = probabilityOf(node, extensions),
            depthRole = roleOf(nullableInt(node, extensions, "role")),
            sticky = node.optBoolean("sticky", false),
            cooldown = node.optInt("cooldown", 0).coerceAtLeast(0),
            delay = node.optInt("delay", 0).coerceAtLeast(0),
            groupName = node.optString("group"),
        )
    }.getOrNull()

    private fun placementOf(node: JSONObject, extensions: JSONObject?): Placement {
        val position = when (nullableInt(node, extensions, "position")) {
            1 -> WorldBookPlacement.POSITION_AFTER_PROMPT
            2 -> WorldBookPlacement.POSITION_AN_BEFORE
            3 -> WorldBookPlacement.POSITION_AN_AFTER
            4 -> WorldBookPlacement.POSITION_DEPTH
            5 -> WorldBookPlacement.POSITION_EM_BEFORE
            6 -> WorldBookPlacement.POSITION_EM_AFTER
            else -> if (node.optString("position") == "after_char") {
                WorldBookPlacement.POSITION_AFTER_PROMPT
            } else {
                WorldBookPlacement.POSITION_BEFORE_PROMPT
            }
        }
        val depth = if (position == WorldBookPlacement.POSITION_DEPTH) {
            (nullableInt(node, extensions, "depth") ?: 0).coerceIn(0, MAX_DEPTH)
        } else {
            0
        }
        return Placement(position, depth)
    }

    private fun probabilityOf(node: JSONObject, extensions: JSONObject?): Double {
        val enabled = field(node, extensions, "useProbability") as? Boolean
        val value = when (val raw = field(node, extensions, "probability")) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        return when {
            enabled == false -> 1.0
            value == null -> 1.0
            value <= 1.0 -> value.coerceIn(0.0, 1.0)
            else -> (value / 100.0).coerceIn(0.0, 1.0)
        }
    }

    private fun entryId(node: JSONObject, mapKey: String?): String =
        node.optString("uid").takeIf(String::isNotBlank)
            ?: node.optString("id").takeIf(String::isNotBlank)
            ?: mapKey?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()

    private fun field(node: JSONObject, extensions: JSONObject?, name: String): Any? =
        extensions?.opt(name)?.takeUnless { it == JSONObject.NULL }
            ?: node.opt(name)?.takeUnless { it == JSONObject.NULL }

    private fun nullableInt(node: JSONObject, extensions: JSONObject?, name: String): Int? =
        when (val value = field(node, extensions, name)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

    private fun roleOf(code: Int?): String = when (code) {
        1 -> WorldBookPlacement.DEPTH_ROLE_USER
        2 -> WorldBookPlacement.DEPTH_ROLE_ASSISTANT
        else -> WorldBookPlacement.DEPTH_ROLE_SYSTEM
    }

    private fun keywordList(raw: Any?): List<String> = when (raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { index ->
            (raw.opt(index) as? String)?.takeIf(String::isNotBlank)
        }
        is String -> listOf(raw).filter(String::isNotBlank)
        else -> emptyList()
    }

    private const val MAX_DEPTH = 10
    private val UUID_PATTERN = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")
}
