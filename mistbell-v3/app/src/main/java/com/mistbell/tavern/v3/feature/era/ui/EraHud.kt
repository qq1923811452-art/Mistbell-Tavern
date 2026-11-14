@file:Suppress("MagicNumber", "TooManyFunctions")

package com.mistbell.tavern.v3.feature.era.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.feature.era.engine.TurnDisplayLine
import com.mistbell.tavern.v3.ui.tavern.TavernPalette

/**
 * 顶部状态栏显示的数据。
 *
 * 引擎目前只把「画面文本」推给前端，没有结构化变量通道，所以这里从最新回合的
 * 文本里粗略抽取日期 / 时段 / 金钱 / 地点。抽不到就留空，状态栏自动收窄，
 * 不会显示错误内容。等引擎加了变量探针（ProbeAsync）后只需要换掉 [parseEraHud]。
 */
internal data class EraHud(
    val date: String? = null,
    val period: String? = null,
    val place: String? = null,
    val money: String? = null,
) {
    val isEmpty: Boolean get() = date == null && period == null && place == null && money == null
}

private val DATE_FULL = Regex("""(\d{1,4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
private val DATE_SEASON = Regex("""([春夏秋冬])\s*(\d{1,2})\s*日""")
private val PERIOD =
    Regex("""(清晨|早上|上午|中午|下午|傍晚|晚上|夜晚|深夜|凌晨|黎明|朝|昼|夕|夜)""")
private val MONEY =
    Regex("""(?:所持金|持有金|金钱|金币|資金|资金|お金)[:：\s]*([\d,，]{2,12})""")
private val PLACE = Regex("""[【\[［]\s*([^】\]］\n]{2,10}?)\s*[】\]］]""")

/** 最近多少行参与状态栏分析：era 每回合都会把状态行重画在屏幕上部。 */
private const val HUD_SCAN_LINES = 60

internal fun parseEraHud(lines: List<TurnDisplayLine>): EraHud {
    if (lines.isEmpty()) return EraHud()
    val tail = lines.takeLast(HUD_SCAN_LINES)
    // 倒序扫：越靠后越接近「当前状态」，避免读到早期剧情的日期。
    val texts = tail.asReversed().map { line -> line.entries.joinToString("") { entry -> entry.segments.joinToString("") { it.text } } }
    var date: String? = null
    var period: String? = null
    var money: String? = null
    var place: String? = null
    for (text in texts) {
        if (date == null) {
            val full = DATE_FULL.find(text)
            if (full != null) {
                date = "${full.groupValues[1]}年${full.groupValues[2]}月${full.groupValues[3]}日"
            } else {
                val season = DATE_SEASON.find(text)
                if (season != null) date = "${season.groupValues[1]} · ${season.groupValues[2]}日"
            }
        }
        if (period == null) {
            period = PERIOD.find(text)?.value
        }
        if (money == null) {
            money = MONEY.find(text)?.groupValues?.get(1)?.replace("，", ",")
        }
        if (place == null) {
            val candidate = PLACE.find(text)?.groupValues?.get(1)?.trim()
            if (candidate != null && isPlausiblePlace(candidate)) place = candidate
        }
        if (date != null && period != null && money != null && place != null) break
    }
    return EraHud(date = date, period = period, place = place, money = money)
}

/** 地点只接受「像地名的短词」：过滤掉按钮标签、数字和标点行。 */
private fun isPlausiblePlace(text: String): Boolean {
    if (text.length !in 2..10) return false
    if (text.any { it.isDigit() }) return false
    if (text.contains('：') || text.contains(':') || text.contains('（') || text.contains('(')) return false
    return text.any { it.code in 0x4E00..0x9FFF }
}

/**
 * 沉浸式顶部状态栏：左侧身份符号与日期，右侧金钱与入口按钮。
 * 背景是向左淡出的玻璃渐变，不占内容区高度感。
 */
@Composable
internal fun EraStatusBar(
    hud: EraHud,
    statusText: String,
    connected: Boolean,
    onOpenLog: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fade =
        remember {
            Brush.horizontalGradient(
                listOf(Color(0xF70B0710), Color(0xE60B0710), Color(0x000B0710)),
            )
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(fade)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "◈",
            color = if (connected) TavernPalette.Gold else TavernPalette.TextTertiary,
            fontSize = 13.sp,
        )
        if (hud.isEmpty) {
            Text(
                text = statusText,
                color = TavernPalette.TextTertiary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        } else {
            hud.date?.let { HudChip(text = it, tone = TavernPalette.Gold, mono = true) }
            hud.period?.let { HudChip(text = it, tone = TavernPalette.PurpleSoft) }
            hud.place?.let { HudChip(text = it, tone = TavernPalette.TextSecondary) }
        }
        Spacer(modifier = Modifier.weight(1f))
        hud.money?.let { value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "◆", color = TavernPalette.GoldDim, fontSize = 10.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = value,
                    color = TavernPalette.Gold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
        HudGhostButton(label = "日志", onClick = onOpenLog)
        HudGhostButton(label = "菜单", onClick = onOpenMenu)
    }
}

@Composable
private fun HudChip(
    text: String,
    tone: Color,
    mono: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(TavernPalette.Glass)
                .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = tone,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
        )
    }
}

@Composable
private fun HudGhostButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0x14FFFFFF))
                .clickableNoRipple(onClick)
                .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = TavernPalette.TextSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

/** 无涟漪点按：状态栏按钮面积小，涟漪会糊住文字。 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
