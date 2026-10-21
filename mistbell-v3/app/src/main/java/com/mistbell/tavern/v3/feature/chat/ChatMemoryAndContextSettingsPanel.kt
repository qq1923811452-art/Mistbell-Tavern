package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Presentation-only grouping for the context, memory API, and embedding settings.
 * The caller retains all state updates and persistence callbacks.
 */
@Composable
internal fun ChatMemoryAndContextSettingsPanel(
    contextBudget: @Composable ColumnScope.() -> Unit,
    memoryApi: @Composable ColumnScope.() -> Unit,
    embeddingDimensions: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = {
            contextBudget()
            memoryApi()
            embeddingDimensions()
        },
    )
}
