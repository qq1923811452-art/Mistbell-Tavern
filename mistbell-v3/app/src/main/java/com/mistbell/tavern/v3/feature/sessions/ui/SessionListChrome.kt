@file:Suppress("FunctionNaming", "MagicNumber")

package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette shared by the dark tavern session list and its row cards. */
internal val TAVERN_NIGHT = Color(0xFF0B0710)

private val TAVERN_BACKDROP_TOP = Color(0xE80E0817)
private val TAVERN_BACKDROP_MIDDLE = Color(0xF2150C22)
private val TAVERN_BACKDROP_BOTTOM = Color(0xFA07040B)
private val TAVERN_BACKDROP_GLOW = Color(0x558B5CF6)

internal val TAVERN_PURPLE = Color(0xFF9B6BFF)
internal val TAVERN_PURPLE_SOFT = Color(0xFFD8B4FE)
private val TAVERN_PURPLE_DEEP = Color(0xFF5B21B6)

internal val TAVERN_TEXT_PRIMARY = Color(0xFFF4EDFF)
internal val TAVERN_TEXT_SECONDARY = Color(0xFFB9A8D4)
internal val TAVERN_TEXT_TERTIARY = Color(0xFF8B7AA6)

internal val TAVERN_BORDER = Color(0x26FFFFFF)
internal val TAVERN_GLASS = Color(0x14FFFFFF)
internal val TAVERN_FAB = Color(0xFF8B5CF6)
internal val TAVERN_DANGER = Color(0xFFFF7A90)

internal val TAVERN_CARD_TOP = Color(0xD91F1630)
internal val TAVERN_CARD_BOTTOM = Color(0xC7140E22)
internal val TAVERN_CARD_SELECTED_TOP = Color(0xE62C2045)
internal val TAVERN_CARD_SELECTED_BOTTOM = Color(0xD91B1230)
internal val TAVERN_CARD_BORDER = Color(0x26FFFFFF)
internal val TAVERN_PIN_BADGE_BG = Color(0x339B6BFF)

internal val TAVERN_AVATAR_START = Color(0xFF7C3AED)
internal val TAVERN_AVATAR_END = Color(0xFFC084FC)
internal val TAVERN_AVATAR_BORDER = Color(0x33FFFFFF)

internal val TAVERN_DARK_COLOR_SCHEME = darkColorScheme(
    primary = TAVERN_PURPLE,
    onPrimary = Color.White,
    primaryContainer = TAVERN_PURPLE_DEEP,
    onPrimaryContainer = TAVERN_TEXT_PRIMARY,
    secondary = TAVERN_PURPLE_SOFT,
    onSecondary = TAVERN_NIGHT,
    background = TAVERN_NIGHT,
    onBackground = TAVERN_TEXT_PRIMARY,
    surface = Color(0xFF171020),
    onSurface = TAVERN_TEXT_PRIMARY,
    surfaceVariant = Color(0xFF241A33),
    onSurfaceVariant = TAVERN_TEXT_SECONDARY,
    outline = TAVERN_BORDER,
    error = TAVERN_DANGER,
    onError = Color.White,
)

private val HEADER_HORIZONTAL_PADDING = 20.dp
private val HEADER_TOP_PADDING = 10.dp
private val HEADER_BOTTOM_PADDING = 14.dp
private val HEADER_TITLE_GAP = 12.dp
private val HEADER_ACTION_GAP = 8.dp
private val HEADER_ACTION_SIZE = 40.dp
private val HEADER_BORDER_WIDTH = 1.dp
private val HEADER_TITLE_SIZE = 32.sp
private val HEADER_TITLE_LETTER_SPACING = 1.sp
private val HEADER_SUBTITLE_TOP_PADDING = 2.dp
private val HEADER_SUBTITLE_SIZE = 13.sp
private val HEADER_SUBTITLE_LETTER_SPACING = 0.5.sp
private val BACK_GLYPH_SIZE = 28.sp
private val SEARCH_GLYPH_SIZE = 17.dp
private val SEARCH_ICON_TINT = TAVERN_TEXT_SECONDARY.copy(alpha = 0.75f)
private val GROUP_ACTION_HORIZONTAL_PADDING = 12.dp
private val GROUP_ACTION_VERTICAL_PADDING = 7.dp
private val GROUP_ACTION_TEXT_SIZE = 12.sp
private val FAB_ICON_SIZE = 20.sp
private val FAB_LABEL_SIZE = 14.sp
private val BACKDROP_GLOW_HEIGHT = 320.dp

private const val BACK_CONTENT_DESCRIPTION = "返回"
private const val GROUP_ACTION_CONTENT_DESCRIPTION = "新建群聊"
private const val SEARCH_PLACEHOLDER_DESCRIPTION = "搜索（占位）"
private const val SEARCH_STROKE_RATIO = 0.11f
private const val SEARCH_RADIUS_RATIO = 0.30f
private const val SEARCH_CENTER_RATIO = 0.42f
private const val SEARCH_HAND_START_RATIO = 0.64f
private const val SEARCH_HAND_END_RATIO = 0.88f

@Composable
internal fun SessionListHeader(
    onBack: () -> Unit,
    showGroupAction: Boolean,
    onOpenGroupDialog: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = HEADER_HORIZONTAL_PADDING,
                top = HEADER_TOP_PADDING,
                end = HEADER_HORIZONTAL_PADDING,
                bottom = HEADER_BOTTOM_PADDING,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.width(HEADER_TITLE_GAP))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "会话",
                    color = TAVERN_TEXT_PRIMARY,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = HEADER_TITLE_SIZE,
                    letterSpacing = HEADER_TITLE_LETTER_SPACING,
                    maxLines = 1,
                )
                Text(
                    text = "继续你的故事",
                    modifier = Modifier.padding(top = HEADER_SUBTITLE_TOP_PADDING),
                    color = TAVERN_TEXT_TERTIARY,
                    fontSize = HEADER_SUBTITLE_SIZE,
                    letterSpacing = HEADER_SUBTITLE_LETTER_SPACING,
                    maxLines = 1,
                )
            }
            if (showGroupAction) {
                GroupActionButton(onClick = onOpenGroupDialog)
                Spacer(modifier = Modifier.width(HEADER_ACTION_GAP))
            }
            SearchPlaceholder()
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(HEADER_ACTION_SIZE)
            .clip(CircleShape)
            .background(TAVERN_GLASS)
            .border(HEADER_BORDER_WIDTH, TAVERN_BORDER, CircleShape)
            .clickable(
                onClickLabel = BACK_CONTENT_DESCRIPTION,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = BACK_CONTENT_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "‹",
            color = TAVERN_TEXT_PRIMARY,
            fontSize = BACK_GLYPH_SIZE,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun GroupActionButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(TAVERN_GLASS)
            .border(HEADER_BORDER_WIDTH, TAVERN_BORDER, CircleShape)
            .clickable(
                onClickLabel = GROUP_ACTION_CONTENT_DESCRIPTION,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = GROUP_ACTION_HORIZONTAL_PADDING,
                vertical = GROUP_ACTION_VERTICAL_PADDING,
            ),
    ) {
        Text(
            text = "＋ 群聊",
            color = TAVERN_TEXT_SECONDARY,
            fontSize = GROUP_ACTION_TEXT_SIZE,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SearchPlaceholder() {
    Box(
        modifier = Modifier
            .size(HEADER_ACTION_SIZE)
            .clip(CircleShape)
            .background(TAVERN_GLASS)
            .border(HEADER_BORDER_WIDTH, TAVERN_BORDER, CircleShape)
            .semantics { contentDescription = SEARCH_PLACEHOLDER_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        SearchGlyph(
            modifier = Modifier.size(SEARCH_GLYPH_SIZE),
            tint = SEARCH_ICON_TINT,
        )
    }
}

@Composable
private fun SearchGlyph(modifier: Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val extent = size.minDimension
        val strokeWidth = extent * SEARCH_STROKE_RATIO
        val center = Offset(extent * SEARCH_CENTER_RATIO, extent * SEARCH_CENTER_RATIO)
        drawCircle(
            color = tint,
            radius = extent * SEARCH_RADIUS_RATIO,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = tint,
            start = Offset(extent * SEARCH_HAND_START_RATIO, extent * SEARCH_HAND_START_RATIO),
            end = Offset(extent * SEARCH_HAND_END_RATIO, extent * SEARCH_HAND_END_RATIO),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun SessionListBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        TAVERN_BACKDROP_TOP,
                        TAVERN_BACKDROP_MIDDLE,
                        TAVERN_BACKDROP_BOTTOM,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BACKDROP_GLOW_HEIGHT)
                .background(
                    Brush.radialGradient(
                        colors = listOf(TAVERN_BACKDROP_GLOW, Color.Transparent),
                    ),
                ),
        )
    }
}

@Composable
internal fun NewSessionFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = TAVERN_FAB,
        contentColor = Color.White,
        icon = {
            Text(
                text = "＋",
                fontSize = FAB_ICON_SIZE,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = "新建对话",
                fontSize = FAB_LABEL_SIZE,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}
