// SillyTavern encodes position/role as small integer enums; they are data, not logic.
@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.feature.worldbook.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps SillyTavern "world info" JSON into the independent Mistbell 3.0 model.
 *
 * The mapping follows the documented field meanings (position numbers, 0-100 probability,
 * keyword arrays) and never mutates the source payload.
 */
object StWorldbookImporter {

    fun import(rawJson: String): WorldbookDocument {
        val root = JSONObject(rawJson)
        val entriesNode = root.opt("entries")
            ?: throw IllegalArgumentException("Worldbook JSON is missing 'entries'.")
        val entries = when (entriesNode) {
            is JSONObject -> entriesNode.keys().asSequence()
                .sorted()
                .mapNotNull { key -> parseEntry(key, entriesNode.optJSONObject(key)) }
                .toList()
            is JSONArray -> (0 until entriesNode.length()).mapNotNull { index ->
                parseEntry(index.toString(), entriesNode.optJSONObject(index))
            }
            else -> throw IllegalArgumentException("'entries' must be an object or array.")
        }
        require(entries.isNotEmpty()) { "Worldbook contains no readable entries." }
        return WorldbookDocument(
            name = root.optString("name", "").trim(),
            entries = entries,
        )
    }

    private fun parseEntry(key: String, node: JSONObject?): WorldbookEntry? {
        val content = node?.optString("content", "")?.trim().orEmpty()
        if (node == null || content.isEmpty()) return null
        val id = sequenceOf("uid", "id", "key")
            .map { node.opt(it)?.toString().orEmpty().trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" }
            ?: key
        val position = readPosition(node)
        val depth = node.optInt("depth", 0).coerceIn(0, 10)
        return WorldbookEntry(
            id = id,
            comment = sequenceOf("comment", "name", "title")
                .map { node.optString(it, "").trim() }
                .firstOrNull(String::isNotEmpty)
                .orEmpty(),
            keys = readKeys(node, "key", "keys"),
            secondaryKeys = readKeys(node, "keysecondary", "secondaryKeys"),
            requireSecondaryMatch = node.optBoolean("selective", false),
            recursive = node.optBoolean("recursive", false),
            content = content,
            constant = node.optBoolean("constant", node.optBoolean("always_on", false)),
            disabled = node.optBoolean("disable", false) || !node.optBoolean("enabled", true),
            order = node.optInt("order", 0),
            insertPosition = position,
            depth = if (position == WorldbookPositions.DEPTH) depth else 0,
            probability = readProbability(node),
            depthRole = readDepthRole(node),
            sticky = node.optBoolean("sticky", false),
            cooldown = node.optInt("cooldown", 0).coerceAtLeast(0),
            delay = node.optInt("delay", 0).coerceAtLeast(0),
            groupName = node.optString("group", "").trim(),
        )
    }

    private fun readKeys(node: JSONObject, primary: String, fallback: String): List<String> {
        val raw = if (node.has(primary)) node.opt(primary) else node.opt(fallback)
        return when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { index ->
                toKeyword(raw.optString(index, ""))
            }
            is String -> raw.split(',')
                .mapNotNull(::toKeyword)
            else -> emptyList()
        }
    }

    private fun toKeyword(value: String): String? =
        value.trim().takeIf { it.isNotEmpty() }

    private fun readPosition(node: JSONObject): String {
        val numeric = when {
            node.has("position") -> node.optInt("position", 0)
            node.has("insertion_position") -> node.optInt("insertion_position", 0)
            else -> 0
        }
        return when (numeric) {
            1 -> WorldbookPositions.AFTER_PROMPT
            2 -> WorldbookPositions.AN_BEFORE
            3 -> WorldbookPositions.AN_AFTER
            4 -> WorldbookPositions.DEPTH
            5 -> WorldbookPositions.EM_BEFORE
            6 -> WorldbookPositions.EM_AFTER
            else -> WorldbookPositions.BEFORE_PROMPT
        }
    }

    private fun readProbability(node: JSONObject): Double {
        val raw = when (val value = node.opt("probability")) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return 1.0
        return if (raw > 1.0) (raw / 100.0).coerceIn(0.0, 1.0) else raw.coerceIn(0.0, 1.0)
    }

    private fun readDepthRole(node: JSONObject): String = when (val raw = node.opt("role")) {
        is Number -> when (raw.toInt()) {
            1 -> WorldbookPositions.ROLE_USER
            2 -> WorldbookPositions.ROLE_ASSISTANT
            else -> WorldbookPositions.ROLE_SYSTEM
        }
        is String -> when (raw.trim().lowercase()) {
            WorldbookPositions.ROLE_USER -> WorldbookPositions.ROLE_USER
            WorldbookPositions.ROLE_ASSISTANT -> WorldbookPositions.ROLE_ASSISTANT
            else -> WorldbookPositions.ROLE_SYSTEM
        }
        else -> WorldbookPositions.ROLE_SYSTEM
    }
}
