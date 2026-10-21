package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry

/**
 * Owns the loaded worldbook entries, loading readiness, and activation scope for a chat session.
 * Loading and activation persistence still delegate to their existing specialized components.
 */
internal data class ChatWorldBookSessionState(
    val entries: List<WorldBookEntry>,
    val ready: Boolean,
    val activation: ChatWorldBookActivationSession,
)

@Composable
internal fun rememberChatWorldBookSessionState(
    context: Context,
    settings: SharedPreferences,
    diagnosticStore: DiagnosticLogStore,
    roleId: String,
    conversationId: String,
    worldbookId: String,
): ChatWorldBookSessionState {
    var entries by remember(context) { mutableStateOf<List<WorldBookEntry>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var selectedCharacterId by remember { mutableStateOf("") }

    val loadingCoordinator = remember(context, diagnosticStore) {
        ChatLibraryLoadingCoordinator(context.applicationContext, diagnosticStore)
    }
    ChatLibraryLoadingLifecycle(
        context = context,
        coordinator = loadingCoordinator,
        selectedCharacterId = selectedCharacterId,
        setSelectedCharacterId = { selectedCharacterId = it },
        activeWorldbookId = worldbookId,
        setCharacterCards = {},
        setWorldbookEntries = { entries = it },
        setWorldbooksReady = { ready = it },
    )
    val activation = rememberChatWorldBookActivationSession(
        settings = settings,
        diagnosticStore = diagnosticStore,
        roleId = roleId,
        conversationId = conversationId,
        worldbookId = worldbookId,
    )
    return remember(entries, ready, activation) {
        ChatWorldBookSessionState(entries = entries, ready = ready, activation = activation)
    }
}
