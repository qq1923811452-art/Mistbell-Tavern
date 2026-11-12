@file:Suppress("MagicNumber", "FunctionNaming")

package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernPageHeader
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill

/**
 * Palette aliases kept for the session list files. The real definitions live in
 * [TavernPalette]; these names survive so rows and cards did not need a mechanical rename.
 */
internal val TAVERN_NIGHT = TavernPalette.Night

internal val TAVERN_PURPLE = TavernPalette.Purple
internal val TAVERN_PURPLE_SOFT = TavernPalette.PurpleSoft

internal val TAVERN_TEXT_PRIMARY = TavernPalette.TextPrimary
internal val TAVERN_TEXT_SECONDARY = TavernPalette.TextSecondary
internal val TAVERN_TEXT_TERTIARY = TavernPalette.TextTertiary

internal val TAVERN_BORDER = TavernPalette.Border
internal val TAVERN_GLASS = TavernPalette.Glass
internal val TAVERN_FAB = TavernPalette.Fab
internal val TAVERN_DANGER = TavernPalette.Danger

internal val TAVERN_CARD_TOP = Color(0xD91F1630)
internal val TAVERN_CARD_BOTTOM = Color(0xC7140E22)
internal val TAVERN_CARD_SELECTED_TOP = Color(0xE62C2045)
internal val TAVERN_CARD_SELECTED_BOTTOM = Color(0xD91B1230)
internal val TAVERN_CARD_BORDER = Color(0x26FFFFFF)
internal val TAVERN_PIN_BADGE_BG = Color(0x339B6BFF)

internal val TAVERN_DARK_COLOR_SCHEME =
    darkColorScheme(
        primary = TAVERN_PURPLE,
        onPrimary = Color.White,
        primaryContainer = TavernPalette.PurpleDeep,
        onPrimaryContainer = TAVERN_TEXT_PRIMARY,
        secondary = TAVERN_PURPLE_SOFT,
        onSecondary = TAVERN_NIGHT,
        background = TAVERN_NIGHT,
        onBackground = TAVERN_TEXT_PRIMARY,
        surface = TavernPalette.Surface,
        onSurface = TAVERN_TEXT_PRIMARY,
        surfaceVariant = TavernPalette.SurfaceRaised,
        onSurfaceVariant = TAVERN_TEXT_SECONDARY,
        outline = TAVERN_BORDER,
        error = TAVERN_DANGER,
        onError = Color.White,
    )

private val HEADER_HORIZONTAL_PADDING = 20.dp
private val HEADER_TOP_PADDING = 10.dp
private val HEADER_BOTTOM_PADDING = 14.dp
private val HEADER_ACTION_TOP_GAP = 14.dp
private val HEADER_ACTION_SPACING = 10.dp
private val HEADER_ACTION_HEIGHT = 46.dp

/**
 * Session list header.
 *
 * "New chat" and "new group" used to live in two different corners — a floating button at the
 * bottom right and a chip in the title row. They now share one action row directly under the title,
 * which is where the eye already is after reading it.
 */
@Composable
internal fun SessionListHeader(
    showGroupAction: Boolean,
    onCreateSession: () -> Unit,
    onOpenGroupDialog: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = HEADER_HORIZONTAL_PADDING,
                    top = HEADER_TOP_PADDING,
                    end = HEADER_HORIZONTAL_PADDING,
                    bottom = HEADER_BOTTOM_PADDING,
                ),
    ) {
        TavernPageHeader(title = "会话", subtitle = "继续你的故事")
        Spacer(modifier = Modifier.height(HEADER_ACTION_TOP_GAP))
        Row(
            modifier = Modifier.fillMaxWidth().height(HEADER_ACTION_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(HEADER_ACTION_SPACING),
        ) {
            TavernPill(
                label = "新建对话",
                onClick = onCreateSession,
                modifier = Modifier.weight(1f).height(HEADER_ACTION_HEIGHT),
                glyph = TavernGlyphName.Plus,
                filled = true,
                contentDescription = "新建对话",
            )
            if (showGroupAction) {
                TavernPill(
                    label = "群聊",
                    onClick = onOpenGroupDialog,
                    modifier = Modifier.height(HEADER_ACTION_HEIGHT),
                    glyph = TavernGlyphName.Group,
                    contentDescription = "新建群聊",
                )
            }
        }
    }
}
