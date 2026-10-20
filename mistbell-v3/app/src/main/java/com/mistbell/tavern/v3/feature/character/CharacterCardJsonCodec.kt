package com.mistbell.tavern.v3.feature.character

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** SillyTavern character-card JSON codec. PNG/CHARX container extraction is handled separately. */
object CharacterCardJsonCodec {
    fun parse(rawJson: String): CharacterCard? = try {
        val root = JSONObject(rawJson)
        val data = root.optJSONObject("data") ?: root
        val book = data.optJSONObject("character_book")?.toString()
        CharacterCard(
            name = data.optString("name", "").trim(),
            description = data.optString("description", ""),
            personality = data.optString("personality", ""),
            scenario = data.optString("scenario", ""),
            firstMessage = data.optString("first_mes", data.optString("first_message", "")),
            exampleDialogue = data.optString("mes_example", data.optString("example_dialogue", "")),
            creatorNotes = data.optString("creator_notes", data.optString("creatorcomment", "")),
            systemPrompt = data.optString("system_prompt", ""),
            postHistoryInstructions = data.optString("post_history_instructions", ""),
            alternateGreetings = data.optStringList("alternate_greetings"),
            tags = data.optStringList("tags"),
            creator = data.optString("creator", ""),
            characterBookJson = book,
        ).takeIf { it.name.isNotBlank() }
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun export(card: CharacterCard): String {
        require(card.name.isNotBlank()) { "Character name is required." }
        val data = JSONObject()
            .put("name", card.name)
            .put("description", card.description)
            .put("personality", card.personality)
            .put("scenario", card.scenario)
            .put("first_mes", card.firstMessage)
            .put("mes_example", card.exampleDialogue)
            .put("creator_notes", card.creatorNotes)
            .put("system_prompt", card.systemPrompt)
            .put("post_history_instructions", card.postHistoryInstructions)
            .put("alternate_greetings", JSONArray(card.alternateGreetings))
            .put("tags", JSONArray(card.tags))
            .put("creator", card.creator)
        card.characterBookJson?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { data.put("character_book", it) }
        }
        return JSONObject().put("spec", "chara_card_v2").put("spec_version", "2.0")
            .put("data", data).toString()
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                if (value is String) add(value)
            }
        }
    }
}
