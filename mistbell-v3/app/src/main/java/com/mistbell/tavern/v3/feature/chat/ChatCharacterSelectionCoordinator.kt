package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences

/** Persists the active character selection using the existing settings key. */
internal class ChatCharacterSelectionCoordinator(
    private val settings: SharedPreferences,
) {
    fun select(characterId: String) {
        if (characterId.isBlank()) {
            settings.edit().remove("active_character_id").apply()
        } else {
            settings.edit().putString("active_character_id", characterId).apply()
        }
    }
}
