package com.mistbell.tavern.v3.feature.character

/** Format-neutral character card fields used by the independent 3.0 importer/exporter. */
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
    /** Raw character_book JSON is preserved for loss-minimizing interchange. */
    val characterBookJson: String? = null,
)
