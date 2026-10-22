package com.mistbell.tavern.v3.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming")
@Composable
fun TavernBottomBar(
    selected: TavernTab,
    onSelect: (TavernTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.35f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            TavernTab.entries.forEach { tab ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (tab) {
                            TavernTab.CHAT -> "聊天"
                            TavernTab.CHARACTERS -> "角色"
                            TavernTab.WORLD_BOOK -> "世界书"
                            TavernTab.SETTINGS -> "设置"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (tab == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
