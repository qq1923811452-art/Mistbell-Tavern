@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.ui.tavern.TavernGlyph
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernPalette

private val BAR_BRUSH =
    Brush.verticalGradient(
        listOf(Color(0xE60A0611), Color(0xFA05030A)),
    )
private val BAR_HEIGHT = 60.dp
private const val SELECTED_PILL_ALPHA = 0.16f

/**
 * Two-tab bottom bar. Icons are stroke glyphs rather than emoji so the bar keeps one visual weight,
 * and the selected tab gets a soft purple pill instead of a colour-only change.
 */
@Suppress("FunctionNaming")
@Composable
fun TavernBottomBar(
    selected: TavernTab,
    onSelect: (TavernTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(BAR_BRUSH)
                .navigationBarsPadding(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, TavernPalette.Border, Color.Transparent),
                        ),
                    ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TavernTab.entries.forEach { tab ->
                TabItem(tab = tab, selected = tab == selected, onSelect = { onSelect(tab) })
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RowScope.TabItem(
    tab: TavernTab,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val label =
        when (tab) {
            TavernTab.CHAT -> "聊天"
            TavernTab.SETTINGS -> "设置"
        }
    val glyph =
        when (tab) {
            TavernTab.CHAT -> TavernGlyphName.ChatTab
            TavernTab.SETTINGS -> TavernGlyphName.SettingsTab
        }
    val tint = if (selected) TavernPalette.PurpleSoft else TavernPalette.TextTertiary
    Box(
        modifier =
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(CircleShape)
                .background(if (selected) TavernPalette.Purple.copy(alpha = SELECTED_PILL_ALPHA) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TavernGlyph(glyph, modifier = Modifier.size(18.dp), tint = tint, strokeWidth = 1.6.dp)
            Text(
                text = label,
                color = tint,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = 0.5.sp,
            )
        }
    }
}
