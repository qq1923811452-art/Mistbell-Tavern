package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences

/**
 * Owns the existing preference updates performed when the active chat scope is switched.
 */
internal class ChatScopeSwitchCoordinator(
    private val settings: SharedPreferences,
) {
    fun persist(previousMemoryScopeId: String, newConversationId: String) {
        settings.edit()
            .remove("memory_extraction_last_success_turn_id_$previousMemoryScopeId")
            .remove("memory_extraction_last_success_turn_id")
            .putString("memory_extraction_conversation_id", newConversationId)
            .apply()
    }
}
