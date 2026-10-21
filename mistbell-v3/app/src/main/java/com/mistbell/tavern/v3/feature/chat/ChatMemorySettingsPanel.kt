package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.feature.chat.ChatEmbeddingDimensionsSettingsSection
import com.mistbell.tavern.v3.feature.memory.MemoryCredentialStore

@Composable
internal fun ChatMemorySettingsPanel(
    settings: SharedPreferences,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    hasSavedCredential: Boolean,
    credentialStore: MemoryCredentialStore,
    diagnosticStore: DiagnosticLogStore,
    selectedDimensions: Int,
    dimensionMenuExpanded: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSavedCredentialChange: (Boolean) -> Unit,
    onStatusChange: (String) -> Unit,
    onDimensionsMenuExpandedChange: (Boolean) -> Unit,
    onDimensionsSelected: (Int) -> Unit,
) {
    ChatMemoryApiSettingsSection(
        settings = settings,
        baseUrl = baseUrl,
        modelId = modelId,
        apiKey = apiKey,
        hasSavedCredential = hasSavedCredential,
        credentialStore = credentialStore,
        diagnosticStore = diagnosticStore,
        onBaseUrlChange = onBaseUrlChange,
        onModelIdChange = onModelIdChange,
        onApiKeyChange = onApiKeyChange,
        onSavedCredentialChange = onSavedCredentialChange,
        onStatusChange = onStatusChange,
    )
    ChatEmbeddingDimensionsSettingsSection(
        selectedDimensions = selectedDimensions,
        menuExpanded = dimensionMenuExpanded,
        onMenuExpandedChange = onDimensionsMenuExpandedChange,
        onDimensionsSelected = onDimensionsSelected,
    )
}
