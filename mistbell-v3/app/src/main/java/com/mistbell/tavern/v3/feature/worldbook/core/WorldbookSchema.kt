package com.mistbell.tavern.v3.feature.worldbook.core

/**
 * Independent worldbook domain model for Mistbell 3.0.
 *
 * Written against the public SillyTavern world-info field semantics; it intentionally does not
 * reuse the legacy app classes so the module stays free of external implementation debt.
 */
object WorldbookPositions {
    const val BEFORE_PROMPT = "before_prompt"
    const val AFTER_PROMPT = "after_prompt"
    const val AN_BEFORE = "an_before"
    const val AN_AFTER = "an_after"
    const val EM_BEFORE = "em_before"
    const val EM_AFTER = "em_after"
    const val DEPTH = "depth"

    const val ROLE_SYSTEM = "system"
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    val supportedPositions: Set<String> = setOf(
        BEFORE_PROMPT, AFTER_PROMPT, AN_BEFORE, AN_AFTER, EM_BEFORE, EM_AFTER, DEPTH,
    )
}

data class WorldbookDocument(
    val name: String = "",
    val entries: List<WorldbookEntry>,
) {
    init {
        require(entries.map(WorldbookEntry::id).distinct().size == entries.size) {
            "Worldbook entry IDs must be unique."
        }
    }
}

data class WorldbookEntry(
    val id: String,
    val comment: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val requireSecondaryMatch: Boolean = false,
    val recursive: Boolean = false,
    val content: String = "",
    val constant: Boolean = false,
    val disabled: Boolean = false,
    val order: Int = 0,
    val insertPosition: String = WorldbookPositions.BEFORE_PROMPT,
    val depth: Int = 0,
    val probability: Double = 1.0,
    val depthRole: String = WorldbookPositions.ROLE_SYSTEM,
    val sticky: Boolean = false,
    val cooldown: Int = 0,
    val delay: Int = 0,
    val groupName: String = "",
) {
    init {
        require(id.isNotBlank()) { "Worldbook entry ID must not be blank." }
        require(content.isNotBlank()) { "Worldbook entry content must not be blank." }
        require(keys.none(String::isBlank) && secondaryKeys.none(String::isBlank)) {
            "Worldbook keywords must not be blank."
        }
        require(insertPosition in WorldbookPositions.supportedPositions) {
            "Unsupported worldbook position: $insertPosition"
        }
        require(depth >= 0) { "Worldbook depth must not be negative." }
        require(probability.isFinite() && probability in 0.0..1.0) {
            "Worldbook probability must be within 0.0..1.0."
        }
        require(cooldown >= 0 && delay >= 0) { "Cooldown and delay must not be negative." }
    }
}

data class ActivatedWorldbookEntry(
    val entry: WorldbookEntry,
    val matchedKeys: List<String>,
    val carriedBySticky: Boolean = false,
)
