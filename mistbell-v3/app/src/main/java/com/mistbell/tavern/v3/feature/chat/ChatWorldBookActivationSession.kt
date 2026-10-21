package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.worldbook.WorldBookActivationState

/**
 * Resolves the persisted activation state for the current role, conversation, and worldbook.
 * Keeping the key and restored state together prevents the screen from mixing scopes.
 */
internal data class ChatWorldBookActivationSession(
    val stateKey: String,
    val activationState: WorldBookActivationState,
)

@Composable
internal fun rememberChatWorldBookActivationSession(
    settings: SharedPreferences,
    diagnosticStore: DiagnosticLogStore,
    roleId: String,
    conversationId: String,
    worldbookId: String,
): ChatWorldBookActivationSession {
    val stateKey = "worldbook_activation_state_${roleId}_${conversationId}_${worldbookId.ifBlank { "none" }}"
    val activationState = remember(settings, diagnosticStore, roleId, conversationId, worldbookId) {
        ChatWorldBookStateStore(settings, diagnosticStore).restore(stateKey)
    }
    return remember(stateKey, activationState) {
        ChatWorldBookActivationSession(
            stateKey = stateKey,
            activationState = activationState,
        )
    }
}
