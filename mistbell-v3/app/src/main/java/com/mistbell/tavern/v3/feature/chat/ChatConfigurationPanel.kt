package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard

@Composable
internal fun ChatConfigurationPanel(
    settings: SharedPreferences,
    selectedCharacterName: String?,
    hasSelectedCharacter: Boolean,
    characterCards: List<StoredCharacterCard>,
    characterMenuExpanded: Boolean,
    onCharacterMenuExpandedChange: (Boolean) -> Unit,
    onCharacterSelected: (String) -> Unit,
    maxTokensText: String,
    usageSummary: String,
    onMaxTokensTextChange: (String) -> Unit,
    transferEnabled: Boolean,
    onImportSession: () -> Unit,
    onExportJson: () -> Unit,
    onExportJsonl: () -> Unit,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    onBaseUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatCharacterSelectionSection(
                selectedCharacterName = selectedCharacterName,
                hasSelectedCharacter = hasSelectedCharacter,
                characterCards = characterCards,
                menuExpanded = characterMenuExpanded,
                onMenuExpandedChange = onCharacterMenuExpandedChange,
                onCharacterSelected = onCharacterSelected,
            )
            ChatGenerationSettingsSection(
                settings = settings,
                maxTokensText = maxTokensText,
                usageSummary = usageSummary,
                onMaxTokensTextChange = onMaxTokensTextChange,
            )
            ChatSessionTransferActions(
                enabled = transferEnabled,
                onImport = onImportSession,
                onExportJson = onExportJson,
                onExportJsonl = onExportJsonl,
            )
            ChatPrimaryApiSettingsSection(
                settings = settings,
                baseUrl = baseUrl,
                modelId = modelId,
                apiKey = apiKey,
                onBaseUrlChange = onBaseUrlChange,
                onModelIdChange = onModelIdChange,
                onApiKeyChange = onApiKeyChange,
            )
        }
    }
}
