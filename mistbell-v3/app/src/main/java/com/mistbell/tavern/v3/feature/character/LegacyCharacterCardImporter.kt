package com.mistbell.tavern.v3.feature.character

import com.mistbell.tavern.v3.feature.worldbook.LegacyWorldbookImporter
import com.mistbell.tavern.v3.feature.worldbook.WorldbookDocument
import org.json.JSONObject

data class CharacterCardImportResult(
    val card: CharacterCard,
    /** Embedded legacy character_book converted to the independent Mistbell worldbook model. */
    val embeddedWorldbook: WorldbookDocument? = null,
)

/**
 * Imports common Character Card V1/V2 JSON exports. It reads V2's nested "data" object and
 * V1's root fields, while separating an embedded character_book into the worldbook domain.
 */
object LegacyCharacterCardImporter {
    fun import(json: String): CharacterCardImportResult {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        val name = data.optString("name", "").trim()
        require(name.isNotEmpty()) { "Legacy character card is missing its name." }
        val id = sequenceOf(data.optString("id", ""), root.optString("id", ""))
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?: "character-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}"
        val greetings = data.optJSONArray("alternate_greetings").toStringList()
        val tags = data.optJSONArray("tags").toStringList()
        val card = CharacterCard(
            id = id,
            name = name,
            description = data.optString("description", ""),
            personality = data.optString("personality", ""),
            scenario = data.optString("scenario", ""),
            firstMessage = data.optString("first_mes", data.optString("firstMessage", "")),
            exampleDialogue = data.optString("mes_example", data.optString("exampleDialogue", "")),
            systemPrompt = data.optString("system_prompt", data.optString("systemPrompt", "")),
            postHistoryInstructions = data.optString(
                "post_history_instructions",
                data.optString("postHistoryInstructions", ""),
            ),
            alternateGreetings = greetings,
            tags = tags,
            creator = data.optString("creator", ""),
            version = data.optString("character_version", data.optString("version", "1")),
        )
        val book = data.optJSONObject("character_book") ?: root.optJSONObject("character_book")
        val worldbook = book?.let { LegacyWorldbookImporter.import(it.toString()) }
        return CharacterCardImportResult(card, worldbook)
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index, "").trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }
}
