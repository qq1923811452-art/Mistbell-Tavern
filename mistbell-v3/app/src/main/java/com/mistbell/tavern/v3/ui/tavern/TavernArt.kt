@file:Suppress("MagicNumber", "TooManyFunctions", "CyclomaticComplexMethod")

package com.mistbell.tavern.v3.ui.tavern

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Optional bitmap overrides for the hand-drawn glyphs.
 *
 * Illustrator-delivered icons drop into `res/drawable-nodpi/` under the exact names in
 * [TavernGlyphName] and are picked up without touching Kotlin: when the drawable is missing the
 * code-drawn glyph renders, so the app never depends on art being present.
 */
object TavernArt {
    private val cache = ConcurrentHashMap<String, Int>()

    @Suppress("DiscouragedApi")
    fun drawableId(
        context: Context,
        resourceName: String,
    ): Int =
        cache.getOrPut(resourceName) {
            runCatching {
                context.resources.getIdentifier(resourceName, "drawable", context.packageName)
            }.getOrDefault(0)
        }

    fun isDelivered(
        context: Context,
        resourceName: String,
    ): Boolean = drawableId(context, resourceName) != 0
}

/** Every icon the UI needs, plus the drawable name an illustrator should deliver for it. */
enum class TavernGlyphName(val drawableName: String) {
    Search("ic_search"),
    Plus("ic_plus"),
    Group("ic_group"),
    Gear("ic_gear"),
    Menu("ic_menu"),
    Send("ic_send"),
    Stop("ic_stop"),
    Refresh("ic_refresh"),
    Back("ic_back"),
    Chevron("ic_chevron"),
    Prompt("ic_prompt"),
    ChatTab("ic_tab_chat"),
    SettingsTab("ic_tab_settings"),
}

/**
 * Vector icon with a bitmap fallback chain: delivered PNG first, code-drawn glyph otherwise.
 * All glyphs are stroke-based and single-colour so `tint` always reads correctly.
 */
@Suppress("FunctionNaming")
@Composable
fun TavernGlyph(
    name: TavernGlyphName,
    modifier: Modifier = Modifier,
    tint: Color = TavernPalette.TextSecondary,
    strokeWidth: Dp = 1.8.dp,
) {
    val context = LocalContext.current
    val override = remember(name) { TavernArt.drawableId(context, name.drawableName) }
    if (override != 0) {
        Image(
            painter = painterResource(override),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = modifier,
        )
    } else {
        Canvas(modifier = modifier) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            when (name) {
                TavernGlyphName.Search -> drawSearchGlyph(tint, stroke)
                TavernGlyphName.Plus -> drawPlusGlyph(tint, stroke)
                TavernGlyphName.Group -> drawGroupGlyph(tint, stroke)
                TavernGlyphName.Gear -> drawGearGlyph(tint, stroke)
                TavernGlyphName.Menu -> drawMenuGlyph(tint)
                TavernGlyphName.Send -> drawSendGlyph(tint, stroke)
                TavernGlyphName.Stop -> drawStopGlyph(tint)
                TavernGlyphName.Refresh -> drawRefreshGlyph(tint, stroke)
                TavernGlyphName.Back -> drawChevronGlyph(tint, stroke, pointsLeft = true)
                TavernGlyphName.Chevron -> drawChevronGlyph(tint, stroke, pointsLeft = false)
                TavernGlyphName.Prompt -> drawPromptGlyph(tint, stroke)
                TavernGlyphName.ChatTab -> drawChatTabGlyph(tint, stroke)
                TavernGlyphName.SettingsTab -> drawGearGlyph(tint, stroke)
            }
        }
    }
}

private fun DrawScope.extent() = size.minDimension

private fun DrawScope.drawSearchGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    drawCircle(tint, e * 0.29f, Offset(e * 0.42f, e * 0.42f), style = stroke)
    drawLine(tint, Offset(e * 0.63f, e * 0.63f), Offset(e * 0.86f, e * 0.86f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawPlusGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    val mid = e / 2f
    val arm = e * 0.32f
    drawLine(tint, Offset(mid - arm, mid), Offset(mid + arm, mid), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(mid, mid - arm), Offset(mid, mid + arm), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawGroupGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    drawCircle(tint, e * 0.13f, Offset(e * 0.35f, e * 0.34f), style = stroke)
    drawCircle(tint, e * 0.11f, Offset(e * 0.65f, e * 0.40f), style = stroke)
    drawArc(tint, 200f, 140f, false, Offset(e * 0.16f, e * 0.46f), Size(e * 0.38f, e * 0.40f), style = stroke)
    drawArc(tint, 200f, 140f, false, Offset(e * 0.47f, e * 0.52f), Size(e * 0.34f, e * 0.34f), style = stroke)
}

private fun DrawScope.drawGearGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    val center = Offset(e / 2f, e / 2f)
    drawCircle(tint, e * 0.18f, center, style = stroke)
    drawCircle(tint, e * 0.31f, center, style = Stroke(width = stroke.width * 0.85f))
    repeat(GEAR_TEETH) { index ->
        val radians = Math.toRadians(index * 360.0 / GEAR_TEETH)
        val innerX = center.x + (e * 0.32f * cos(radians)).toFloat()
        val innerY = center.y + (e * 0.32f * sin(radians)).toFloat()
        val outerX = center.x + (e * 0.44f * cos(radians)).toFloat()
        val outerY = center.y + (e * 0.44f * sin(radians)).toFloat()
        drawLine(
            color = tint,
            start = Offset(innerX, innerY),
            end = Offset(outerX, outerY),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawMenuGlyph(tint: Color) {
    val e = extent()
    listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
        drawCircle(tint, e * 0.055f, Offset(e / 2f, e * fraction))
    }
}

private fun DrawScope.drawSendGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    val path =
        Path().apply {
            moveTo(e * 0.16f, e * 0.28f)
            lineTo(e * 0.86f, e * 0.50f)
            lineTo(e * 0.16f, e * 0.72f)
            lineTo(e * 0.30f, e * 0.50f)
            close()
        }
    drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawStopGlyph(tint: Color) {
    val e = extent()
    drawRoundRect(
        color = tint,
        topLeft = Offset(e * 0.30f, e * 0.30f),
        size = Size(e * 0.40f, e * 0.40f),
        cornerRadius = CornerRadius(e * 0.08f),
    )
}

private fun DrawScope.drawRefreshGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    drawArc(tint, 40f, 280f, false, Offset(e * 0.22f, e * 0.22f), Size(e * 0.56f, e * 0.56f), style = stroke)
    drawLine(tint, Offset(e * 0.70f, e * 0.12f), Offset(e * 0.70f, e * 0.34f), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(e * 0.70f, e * 0.34f), Offset(e * 0.48f, e * 0.34f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawChevronGlyph(
    tint: Color,
    stroke: Stroke,
    pointsLeft: Boolean,
) {
    val e = extent()
    val near = if (pointsLeft) 0.64f else 0.36f
    val far = if (pointsLeft) 0.36f else 0.64f
    val path =
        Path().apply {
            moveTo(e * near, e * 0.28f)
            lineTo(e * far, e * 0.50f)
            lineTo(e * near, e * 0.72f)
        }
    drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawPromptGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    val sheet =
        Path().apply {
            moveTo(e * 0.24f, e * 0.26f)
            lineTo(e * 0.66f, e * 0.26f)
            lineTo(e * 0.78f, e * 0.38f)
            lineTo(e * 0.78f, e * 0.74f)
            lineTo(e * 0.24f, e * 0.74f)
            close()
        }
    drawPath(sheet, tint, style = stroke)
    drawLine(tint, Offset(e * 0.34f, e * 0.48f), Offset(e * 0.68f, e * 0.48f), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(e * 0.34f, e * 0.60f), Offset(e * 0.58f, e * 0.60f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawChatTabGlyph(
    tint: Color,
    stroke: Stroke,
) {
    val e = extent()
    val bubble =
        Path().apply {
            moveTo(e * 0.20f, e * 0.34f)
            lineTo(e * 0.80f, e * 0.34f)
            lineTo(e * 0.80f, e * 0.66f)
            lineTo(e * 0.44f, e * 0.66f)
            lineTo(e * 0.30f, e * 0.78f)
            lineTo(e * 0.31f, e * 0.66f)
            lineTo(e * 0.20f, e * 0.66f)
            close()
        }
    drawPath(bubble, tint, style = stroke)
}

private const val GEAR_TEETH = 8
