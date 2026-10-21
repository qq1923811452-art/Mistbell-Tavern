package com.mistbell.tavern.v3.feature.worldbook.core

import org.json.JSONArray
import org.json.JSONObject

/** Canonical, versioned JSON for the independent Mistbell 3.0 worldbook format. */
object WorldbookJsonCodec {
    const val FORMAT_VERSION = 1
    private const val FORMAT_NAME = "mistbell-worldbook"

    fun encode(document: WorldbookDocument): String {
        val entries = JSONArray()
        document.entries.forEach { entry ->
            entries.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("comment", entry.comment)
                    .put("keys", JSONArray(entry.keys))
                    .put("secondary_keys", JSONArray(entry.secondaryKeys))
                    .put("require_secondary_match", entry.requireSecondaryMatch)
                    .put("recursive", entry.recursive)
                    .put("content", entry.content)
                    .put("constant", entry.constant)
                    .put("disabled", entry.disabled)
                    .put("order", entry.order)
                    .put("position", entry.insertPosition)
                    .put("depth", entry.depth)
                    .put("probability", entry.probability)
                    .put("role", entry.depthRole)
                    .put("sticky", entry.sticky)
                    .put("cooldown", entry.cooldown)
                    .put("delay", entry.delay)
                    .put("group", entry.groupName),
            )
        }
        return JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", FORMAT_VERSION)
            .put("name", document.name)
            .put("entries", entries)
            .toString(2)
    }

    fun decode(rawJson: String): WorldbookDocument {
        val root = JSONObject(rawJson)
        val version = root.optInt("version", FORMAT_VERSION)
        require(version == FORMAT_VERSION) { "Unsupported worldbook format version: $version" }
        val array = root.optJSONArray("entries")
            ?: throw IllegalArgumentException("Worldbook JSON is missing 'entries'.")
        val entries = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index) ?: continue
                add(decodeEntry(node))
            }
        }
        return WorldbookDocument(name = root.optString("name", ""), entries = entries)
    }

    private fun decodeEntry(node: JSONObject): WorldbookEntry {
        val position = node.optString("position", WorldbookPositions.BEFORE_PROMPT)
        return WorldbookEntry(
            id = node.optString("id", "").trim().also {
                require(it.isNotEmpty()) { "Worldbook entry is missing 'id'." }
            },
            comment = node.optString("comment", ""),
            keys = node.optJSONArray("keys").toStringList(),
            secondaryKeys = node.optJSONArray("secondary_keys").toStringList(),
            requireSecondaryMatch = node.optBoolean("require_secondary_match", false),
            recursive = node.optBoolean("recursive", false),
            content = node.optString("content", "").also {
                require(it.isNotBlank()) { "Worldbook entry '$it' has empty content." }
            },
            constant = node.optBoolean("constant", false),
            disabled = node.optBoolean("disabled", false),
            order = node.optInt("order", 0),
            insertPosition = position.takeIf { it in WorldbookPositions.supportedPositions }
                ?: WorldbookPositions.BEFORE_PROMPT,
            depth = node.optInt("depth", 0).coerceIn(0, WorldbookPlanner.MAX_DEPTH),
            probability = node.optDouble("probability", 1.0).coerceIn(0.0, 1.0),
            depthRole = node.optString("role", WorldbookPositions.ROLE_SYSTEM).let { role ->
                if (role == WorldbookPositions.ROLE_USER || role == WorldbookPositions.ROLE_ASSISTANT) {
                    role
                } else {
                    WorldbookPositions.ROLE_SYSTEM
                }
            },
            sticky = node.optBoolean("sticky", false),
            cooldown = node.optInt("cooldown", 0).coerceAtLeast(0),
            delay = node.optInt("delay", 0).coerceAtLeast(0),
            groupName = node.optString("group", ""),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val value = optString(index, "").trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
