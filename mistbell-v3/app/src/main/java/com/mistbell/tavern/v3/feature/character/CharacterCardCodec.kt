package com.mistbell.tavern.v3.feature.character

import org.json.JSONArray
import org.json.JSONObject

/** Canonical JSON codec for the documented Mistbell character-card v1 format. */
object CharacterCardCodec {
    fun encode(card: CharacterCard): String {
        val root = JSONObject()
            .put("format", "mistbell-character")
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "character",
                JSONObject()
                    .put("id", card.id)
                    .put("name", card.name)
                    .put("description", card.description)
                    .put("personality", card.personality)
                    .put("scenario", card.scenario)
                    .put("firstMessage", card.firstMessage)
                    .put("exampleDialogue", card.exampleDialogue)
                    .put("systemPrompt", card.systemPrompt)
                    .put("postHistoryInstructions", card.postHistoryInstructions)
                    .put("alternateGreetings", card.alternateGreetings.toJsonArray())
                    .put("tags", card.tags.toJsonArray())
                    .put("creator", card.creator)
                    .put("version", card.version),
            )
        return root.toString()
    }

    fun decode(json: String): CharacterCard {
        val root = JSONObject(json)
        require(root.optString("format") == "mistbell-character") {
            "Not a Mistbell character card."
        }
        val version = root.optInt("schemaVersion", -1)
        require(version == SCHEMA_VERSION) { "Unsupported Mistbell character schema version: $version" }
        val source = root.optJSONObject("character")
            ?: throw IllegalArgumentException("Character card is missing the 'character' object.")
        return CharacterCard(
            id = source.requiredString("id"),
            name = source.requiredString("name"),
            description = source.optString("description", ""),
            personality = source.optString("personality", ""),
            scenario = source.optString("scenario", ""),
            firstMessage = source.optString("firstMessage", ""),
            exampleDialogue = source.optString("exampleDialogue", ""),
            systemPrompt = source.optString("systemPrompt", ""),
            postHistoryInstructions = source.optString("postHistoryInstructions", ""),
            alternateGreetings = source.optJSONArray("alternateGreetings").toStringList(),
            tags = source.optJSONArray("tags").toStringList(),
            creator = source.optString("creator", ""),
            version = source.optString("version", "1"),
        )
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = optString(key, "").trim()
        require(value.isNotEmpty()) { "Character field '$key' must not be blank." }
        return value
    }

    private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach(array::put)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index, "").trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private const val SCHEMA_VERSION = 1
}
