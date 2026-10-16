package com.mistbell.tavern.v3.feature.worldbook

/**
 * Versioned Mistbell worldbook model. It is intentionally independent of legacy app classes;
 * legacy formats are translated into this model by LegacyWorldbookImporter.
 */
data class WorldbookDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val entries: List<WorldbookEntry>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported Mistbell worldbook schema version: $schemaVersion" }
        require(entries.map(WorldbookEntry::id).distinct().size == entries.size) {
            "Worldbook entry IDs must be unique."
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class WorldbookEntry(
    val id: String,
    val title: String,
    val content: String,
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val requireSecondaryMatch: Boolean = false,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val recursive: Boolean = false,
    val priority: Int = 0,
    val order: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "Worldbook entry ID must not be blank." }
        require(title.isNotBlank()) { "Worldbook entry title must not be blank." }
        require(content.isNotBlank()) { "Worldbook entry content must not be blank." }
        require(keys.none(String::isBlank) && secondaryKeys.none(String::isBlank)) {
            "Worldbook keywords must not be blank."
        }
    }
}
