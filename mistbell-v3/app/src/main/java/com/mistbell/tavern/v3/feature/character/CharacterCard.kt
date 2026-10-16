package com.mistbell.tavern.v3.feature.character

/**
 * Mistbell character-card v1. Stable identity and definition fields are separated from
 * volatile chat history so prompt assembly can keep the stable prefix unchanged.
 */
data class CharacterCard(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val version: String = "1",
) {
    init {
        require(id.isNotBlank()) { "Character card ID must not be blank." }
        require(name.isNotBlank()) { "Character name must not be blank." }
        require(alternateGreetings.none(String::isBlank) && tags.none(String::isBlank)) {
            "Character greetings and tags must not be blank."
        }
    }

    /** Stable character definition only; conversation-specific state must be appended later. */
    fun stableDefinition(): String = buildList {
        add("Name: ${name.trim()}")
        description.trim().takeIf(String::isNotEmpty)?.let { add("Description: $it") }
        personality.trim().takeIf(String::isNotEmpty)?.let { add("Personality: $it") }
        scenario.trim().takeIf(String::isNotEmpty)?.let { add("Scenario: $it") }
        systemPrompt.trim().takeIf(String::isNotEmpty)?.let { add("System instructions: $it") }
        postHistoryInstructions.trim().takeIf(String::isNotEmpty)?.let {
            add("Post-history instructions: $it")
        }
    }.joinToString("\n\n")
}
