package com.mistbell.tavern.v3.feature.worldbook

/** Pure domain models; deliberately independent from Room and the legacy app package. */
data class WorldBook(
    val id: String,
    val name: String,
    val entries: List<WorldBookEntry>,
)

data class WorldBookEntry(
    val id: String,
    val bookId: String,
    val comment: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    /** Require at least one secondary keyword when primary keys match (SillyTavern selective). */
    val requireSecondaryMatch: Boolean = false,
    /** Allow this entry's content to participate in bounded recursive activation. */
    val recursive: Boolean = false,
    val content: String = "",
    val constant: Boolean = false,
    val disable: Boolean = false,
    val order: Int = 0,
    val insertPosition: String = WorldBookPlacement.POSITION_BEFORE_PROMPT,
    val depth: Int = 0,
    val probability: Double = 1.0,
    val depthRole: String = WorldBookPlacement.DEPTH_ROLE_SYSTEM,
    val sticky: Boolean = false,
    val cooldown: Int = 0,
    val delay: Int = 0,
    val groupName: String = "",
)

data class ActivatedWorldBookEntry(
    val entry: WorldBookEntry,
    val matchedKeys: List<String>,
)
