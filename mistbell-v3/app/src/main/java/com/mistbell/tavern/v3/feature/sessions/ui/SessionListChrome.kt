@file:Suppress("MagicNumber", "FunctionNaming")

package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.ui.tavern.TavernGlyph
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernPageHeader
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape

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
private val HEADER_ENTRY_TOP_GAP = 12.dp
private val HEADER_ACTION_SPACING = 10.dp
private val HEADER_ACTION_HEIGHT = 46.dp
private val ERA_ENTRY_BADGE_SIZE = 44.dp
private val ERA_ENTRY_BADGE_BG = Color(0x2EFFCE8A)
private val ERA_ENTRY_BADGE_BORDER = Color(0x66FFCE8A)
private val ERA_ENTRY_BORDER = Color(0x38FFCE8A)
private val ERA_ENTRY_BG_TOP = Color(0xE62C1830)
private val ERA_ENTRY_BG_BOTTOM = Color(0xE6150E22)
private val ERA_ENTRY_BRUSH = Brush.linearGradient(listOf(ERA_ENTRY_BG_TOP, ERA_ENTRY_BG_BOTTOM))

/**
 * Entry to the Gensokyo / eraTW adaptation. It sits above the session actions because it is a
 * different way to start a story, not another session mutation.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EraGameEntry(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.Card)
                .background(ERA_ENTRY_BRUSH)
                .border(1.dp, ERA_ENTRY_BORDER, TavernShape.Card)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "进入幻想乡",
                    onLongClickLabel = "导入或更新游戏 ZIP",
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(ERA_ENTRY_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(ERA_ENTRY_BADGE_BG)
                    .border(1.dp, ERA_ENTRY_BADGE_BORDER, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "乡",
                color = TavernPalette.Gold,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "幻想乡",
                    color = TavernPalette.TextPrimary,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    text = "eraTW",
                    color = TavernPalette.Gold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(ERA_ENTRY_BADGE_BG)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Text(
                text = "点击进入游戏 · 长按导入/更新",
                modifier = Modifier.padding(top = 2.dp),
                color = TavernPalette.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        TavernGlyph(
            name = TavernGlyphName.Chevron,
            modifier = Modifier.size(16.dp),
            tint = TavernPalette.GoldDim,
        )
    }
}

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
    onImportEraGame: () -> Unit,
    onOpenEraGame: () -> Unit,
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
        Spacer(modifier = Modifier.height(HEADER_ENTRY_TOP_GAP))
        EraGameEntry(onClick = onOpenEraGame, onLongClick = onImportEraGame)
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
