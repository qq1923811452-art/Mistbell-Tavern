package com.mistbell.tavern.v3.feature.worldbook

import org.json.JSONArray
import org.json.JSONObject

/**
 * One-way compatibility adapter for common SillyTavern-style worldbook exports.
 * It preserves constant entries even when they have no keywords.
 */
object LegacyWorldbookImporter {
    fun import(json: String): WorldbookDocument {
        val root = JSONObject(json)
        val source = root.opt("entries")
            ?: throw IllegalArgumentException("Legacy worldbook is missing 'entries'.")
        val parsed = when (source) {
            is JSONObject -> source.keys().asSequence().sorted().map { key ->
                val entry = source.optJSONObject(key)
                    ?: throw IllegalArgumentException("Legacy worldbook entry '$key' must be an object.")
                parseEntry(key, entry)
            }.toList()
            is JSONArray -> buildList {
                for (index in 0 until source.length()) {
                    val entry = source.optJSONObject(index)
                        ?: throw IllegalArgumentException("Legacy worldbook entry $index must be an object.")
                    add(parseEntry(index.toString(), entry))
                }
            }
            else -> throw IllegalArgumentException("Legacy worldbook 'entries' must be an object or array.")
        }
        val unique = parsed.mapIndexed { index, entry ->
            if (entry.id.isBlank()) entry.copy(id = "legacy-$index") else entry
        }.distinctBy(WorldbookEntry::id)
        require(unique.size == parsed.size) { "Legacy worldbook contains duplicate entry IDs." }
        return WorldbookDocument(entries = unique)
    }

    private fun parseEntry(fallbackId: String, source: JSONObject): WorldbookEntry {
        val uid = source.opt("uid")?.toString()?.takeIf(String::isNotBlank) ?: fallbackId
        val title = sequenceOf("comment", "name", "title")
            .map { source.optString(it, "").trim() }
            .firstOrNull(String::isNotEmpty) ?: "Entry $uid"
        val content = source.optString("content", "").trim()
        require(content.isNotEmpty()) { "Legacy worldbook entry '$uid' has no content." }
        val disabled = source.optBoolean("disable", false)
        return WorldbookEntry(
            id = uid,
            title = title,
            content = content,
            keys = source.readKeywords("key", "keys"),
            secondaryKeys = source.readKeywords("keysecondary", "secondaryKeys"),
            requireSecondaryMatch = source.optBoolean("selective", false),
            enabled = source.optBoolean("enabled", !disabled) && !disabled,
            constant = source.optBoolean("constant", source.optBoolean("always_on", false)),
            recursive = source.optBoolean("recursive", false),
            priority = source.optInt("priority", source.optInt("order", 0)),
            order = source.optInt("order", 0),
        )
    }

    private fun JSONObject.readKeywords(primary: String, fallback: String): List<String> {
        val raw = if (has(primary)) opt(primary) else opt(fallback)
        return when (raw) {
            is JSONArray -> buildList {
                for (index in 0 until raw.length()) {
                    raw.optString(index, "").trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
            is String -> raw.trim().takeIf(String::isNotEmpty)?.let(::listOf) ?: emptyList()
            else -> emptyList()
        }
    }
}
