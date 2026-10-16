package com.mistbell.tavern.v3.feature.worldbook

import org.json.JSONArray
import org.json.JSONObject

/** Canonical JSON codec for the documented Mistbell worldbook v1 format. */
object WorldbookCodec {
    fun encode(document: WorldbookDocument): String {
        val root = JSONObject()
            .put("format", "mistbell-worldbook")
            .put("schemaVersion", document.schemaVersion)
        val entries = JSONArray()
        document.entries.forEach { entry ->
            entries.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("content", entry.content)
                    .put("keys", entry.keys.toJsonArray())
                    .put("secondaryKeys", entry.secondaryKeys.toJsonArray())
                    .put("requireSecondaryMatch", entry.requireSecondaryMatch)
                    .put("enabled", entry.enabled)
                    .put("constant", entry.constant)
                    .put("recursive", entry.recursive)
                    .put("priority", entry.priority)
                    .put("order", entry.order),
            )
        }
        root.put("entries", entries)
        return root.toString()
    }

    fun decode(json: String): WorldbookDocument {
        val root = JSONObject(json)
        require(root.optString("format") == "mistbell-worldbook") { "Not a Mistbell worldbook document." }
        val version = root.optInt("schemaVersion", -1)
        require(version == WorldbookDocument.CURRENT_SCHEMA_VERSION) {
            "Unsupported Mistbell worldbook schema version: $version"
        }
        val source = root.optJSONArray("entries")
            ?: throw IllegalArgumentException("Worldbook entries must be a JSON array.")
        val entries = buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index)
                    ?: throw IllegalArgumentException("Worldbook entry $index must be an object.")
                add(
                    WorldbookEntry(
                        id = item.requiredString("id"),
                        title = item.requiredString("title"),
                        content = item.requiredString("content"),
                        keys = item.optJSONArray("keys").toStringList(),
                        secondaryKeys = item.optJSONArray("secondaryKeys").toStringList(),
                        requireSecondaryMatch = item.optBoolean("requireSecondaryMatch", false),
                        enabled = item.optBoolean("enabled", true),
                        constant = item.optBoolean("constant", false),
                        recursive = item.optBoolean("recursive", false),
                        priority = item.optInt("priority", 0),
                        order = item.optInt("order", 0),
                    ),
                )
            }
        }
        return WorldbookDocument(schemaVersion = version, entries = entries)
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = optString(key, "").trim()
        require(value.isNotEmpty()) { "Worldbook field '$key' must not be blank." }
        return value
    }

    private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach { array.put(it) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index, "").trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
