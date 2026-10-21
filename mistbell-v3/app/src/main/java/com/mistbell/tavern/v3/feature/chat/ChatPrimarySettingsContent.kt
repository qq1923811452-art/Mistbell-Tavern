package com.mistbell.tavern.v3.feature.chat

import androidx.compose.runtime.Composable

/**
 * Presentation grouping for the primary chat settings controls.
 * Each control's state and event handlers remain owned by ChatScreen.
 */
@Composable
internal fun ChatPrimarySettingsContent(
    characterSelector: @Composable () -> Unit,
    generationLimit: @Composable () -> Unit,
    sessionTransfer: @Composable () -> Unit,
    apiEndpoint: @Composable () -> Unit,
) {
    characterSelector()
    generationLimit()
    sessionTransfer()
    apiEndpoint()
}
