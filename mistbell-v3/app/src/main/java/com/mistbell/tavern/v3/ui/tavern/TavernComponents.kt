@file:Suppress("MagicNumber", "MatchingDeclarationName")

package com.mistbell.tavern.v3.ui.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Corner radii used everywhere, so cards stop disagreeing with each other. */
object TavernShape {
    val Card = RoundedCornerShape(20.dp)
    val CardSmall = RoundedCornerShape(14.dp)
    val Pill = CircleShape
    val Sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
}

private val CARD_BRUSH = Brush.verticalGradient(
    listOf(Color(0xD91F1630), Color(0xC7140E22)),
)

private val HEADER_TITLE_SIZE = 32.sp

/** Serif page title with a quieter subtitle, matching the session list header. */
@Suppress("FunctionNaming")
@Composable
fun TavernPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TavernPalette.TextPrimary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = HEADER_TITLE_SIZE,
                letterSpacing = 1.sp,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    color = TavernPalette.TextTertiary,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
            }
        }
        trailing()
    }
}

/** Low-contrast glass panel: the only card surface used by the redesign. */
@Suppress("FunctionNaming")
@Composable
fun TavernGlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = TavernShape.Card,
    borderColor: Color = TavernPalette.Border,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush = CARD_BRUSH, shape = shape)
            .border(width = 1.dp, color = borderColor, shape = shape),
        content = content,
    )
}

/** Small uppercase-ish section caption with a hairline rule. */
@Suppress("FunctionNaming")
@Composable
fun TavernSectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(TavernPalette.Purple),
        )
        Text(
            text = text,
            color = TavernPalette.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(TavernPalette.Border, Color.Transparent))),
        )
    }
}

/** One-line destination row: title, quiet subtitle, chevron. */
@Suppress("FunctionNaming")
@Composable
fun TavernNavRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TavernPalette.TextPrimary, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = TavernPalette.TextTertiary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        TavernGlyph(TavernGlyphName.Chevron, modifier = Modifier.size(16.dp), tint = TavernPalette.TextTertiary)
    }
}

/** Filled (primary) or glass (secondary) pill; the only two button looks in the app. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun TavernPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: TavernGlyphName? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val shape = TavernShape.Pill
    val background = if (filled) {
        Brush.horizontalGradient(listOf(TavernPalette.Fab, TavernPalette.PurpleDeep))
    } else {
        Brush.horizontalGradient(listOf(TavernPalette.GlassStrong, TavernPalette.Glass))
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(brush = background, shape = shape)
            .border(1.dp, if (filled) Color.Transparent else TavernPalette.Border, shape)
            .clickable(
                enabled = enabled,
                onClickLabel = contentDescription ?: label,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (glyph != null) {
            TavernGlyph(
                name = glyph,
                modifier = Modifier.size(if (filled) 16.dp else 15.dp),
                tint = if (filled) Color.White else TavernPalette.TextSecondary,
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = label,
            color = if (filled) Color.White else TavernPalette.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Circular icon button used in top bars. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun TavernIconButton(
    glyph: TavernGlyphName,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = TavernPalette.TextSecondary,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(TavernPalette.Glass)
            .border(1.dp, TavernPalette.Border, CircleShape)
            .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TavernGlyph(glyph, modifier = Modifier.size(size * 0.42f), tint = tint)
    }
}

/** Draggable handle + title used by every bottom sheet. */
@Suppress("FunctionNaming")
@Composable
fun TavernSheetHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp)
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(TavernPalette.BorderStrong),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            color = TavernPalette.TextPrimary,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp),
                color = TavernPalette.TextTertiary,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
