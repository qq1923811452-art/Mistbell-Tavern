package com.mistbell.tavern.v3.feature.character

import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports SillyTavern V1/V2/V3 JSON card payloads into the independent Mistbell 3.0 model.
 * PNG metadata extraction and CHARX archive unpacking are intentionally separate container work.
 */
object LegacyCharacterCardImporter {
    fun import(json: String): CharacterCard {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        val name = data.optString("name", "").trim()
        require(name.isNotEmpty()) { "Legacy character card is missing its name." }

        val book = data.optJSONObject("character_book") ?: root.optJSONObject("character_book")
        return CharacterCard(
            name = name,
            description = data.optString("description", ""),
            personality = data.optString("personality", ""),
            scenario = data.optString("scenario", ""),
            firstMessage = data.optString("first_mes", data.optString("first_message", data.optString("firstMessage", ""))),
            exampleDialogue = data.optString("mes_example", data.optString("example_dialogue", data.optString("exampleDialogue", ""))),
            creatorNotes = data.optString("creator_notes", data.optString("creatorcomment", "")),
            systemPrompt = data.optString("system_prompt", data.optString("systemPrompt", "")),
            postHistoryInstructions = data.optString(
                "post_history_instructions",
                data.optString("postHistoryInstructions", ""),
            ),
            alternateGreetings = data.optJSONArray("alternate_greetings").toStringList(),
            tags = data.optJSONArray("tags").toStringList(),
            creator = data.optString("creator", ""),
            characterBookJson = book?.toString(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                opt(index).let { value ->
                    if (value is String) value.trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }
    }
}
