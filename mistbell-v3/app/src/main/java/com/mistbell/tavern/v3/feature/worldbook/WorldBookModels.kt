package com.mistbell.tavern.v3.feature.worldbook

/**
 * Worldbook document as stored by Mistbell 3.0.
 *
 * The type is persistence-neutral: the JSON codec, the ST importer and the activation planner
 * all use it directly, so no mapping layer is required between them.
 */
data class WorldBook(
    val id: String,
    val name: String,
    val entries: List<WorldBookEntry>,
)

/**
 * A single lore entry.
 *
 * [keys] and [secondaryKeys] are matched case-insensitively; [requireSecondaryMatch] turns the
 * secondary list into a gate. [recursive] allows the entry to feed later matching passes, and
 * [insertPosition]/[depth]/[depthRole] decide where the content lands in the prompt.
 * [sticky], [cooldown] and [delay] schedule reactivation across turns.
 */
data class WorldBookEntry(
    val id: String,
    val bookId: String,
    val comment: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val requireSecondaryMatch: Boolean = false,
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

/** An activated entry together with the keyword hits that selected it. */
data class ActivatedWorldBookEntry(
    val entry: WorldBookEntry,
    val matchedKeys: List<String>,
)
