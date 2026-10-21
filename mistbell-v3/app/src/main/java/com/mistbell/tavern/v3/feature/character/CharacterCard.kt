package com.mistbell.tavern.v3.feature.character

/**
 * Character definition independent of any interchange format.
 *
 * Importers fill these fields from PNG/JSON cards; exporters write them back. Raw character book
 * JSON stays untouched so round trips do not lose unknown lore data.
 */
data class CharacterCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val exampleDialogue: String = "",
    val creatorNotes: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterBookJson: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Character name is required." }
    }

    /**
     * Prompt-stable definition block. Conversation state is assembled elsewhere so this output
     * can be cached and reused across turns.
     */
    fun stableDefinition(): String {
        val blocks = mutableListOf<String>()
        fun append(label: String, value: String) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) blocks += "$label: $trimmed"
        }
        append("Name", name)
        append("Description", description)
        append("Personality", personality)
        append("Scenario", scenario)
        exampleDialogue.trim().takeIf(String::isNotEmpty)?.let { blocks += "Example dialogue:\n$it" }
        append("System instructions", systemPrompt)
        append("Post-history instructions", postHistoryInstructions)
        return blocks.joinToString("\n\n")
    }
}
