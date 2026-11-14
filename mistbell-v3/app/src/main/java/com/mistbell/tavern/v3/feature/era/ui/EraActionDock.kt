@file:Suppress("MagicNumber", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod")

package com.mistbell.tavern.v3.feature.era.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mistbell.tavern.v3.feature.era.engine.TurnAwaiting
import com.mistbell.tavern.v3.feature.era.engine.TurnButton
import com.mistbell.tavern.v3.feature.era.engine.TurnButtonItem
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernShape

/** 右侧动作栏固定宽度：横屏 16:9 上大约占 1/3，文本区仍能放下 era 的整行。 */
internal val ERA_DOCK_WIDTH = 292.dp

private const val PRIMARY_SLOTS = 8

private val HOT_LABELS =
    listOf(
        "返回", "移动", "对话", "交谈", "会话", "休息", "睡觉", "时间",
        "道具", "物品", "状态", "能力", "关系", "存档", "读取", "设定", "菜单",
        "调查", "查看", "购物", "吃饭", "战斗", "技能", "任务", "日志", "继续",
    )

private val GROUP_ORDER =
    listOf("常用", "场所", "剧情", "档案", "设定", "调试", "杂项", "系统", "其他")

/**
 * 右侧动作栏：把引擎每回合吐出的按钮重新排成网格。
 *
 * 旧界面把这些按钮塞在底部一行横向滚动里，按钮越多越难按；现在固定 2 列、
 * 最多 8 个热按钮，其余收进「更多」抽屉。按钮的原始值仍然原样回传，不改语义。
 */
@Composable
internal fun EraActionDock(
    items: List<TurnButtonItem>,
    awaiting: TurnAwaiting?,
    onButton: (TurnButton) -> Unit,
    onSend: (String) -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val ordered = remember(items) { orderForDock(items) }
    val primary = ordered.take(PRIMARY_SLOTS)
    val overflow = ordered.drop(PRIMARY_SLOTS)

    Column(
        modifier =
            modifier
                .width(ERA_DOCK_WIDTH)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color(0x000B0710), Color(0xE60B0710))))
                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DockHeader(awaiting = awaiting, count = ordered.size)
        val current = awaiting
        if (current == null) {
            DockHint("正在连接引擎…")
        } else if (current.isEnded) {
            EndedPanel(awaiting = current, onRestart = onRestart)
        } else if (current.inputType == "Void" || !current.acceptsInput) {
            DockHint("剧本推进中…")
        } else {
            if (primary.isEmpty()) {
                DockHint("轻触左侧文本继续")
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = primary, key = { it.button.value }) { item ->
                    DockActionButton(label = item.label) { onButton(item.button) }
                }
            }
            if (overflow.isNotEmpty()) {
                MoreButton(count = overflow.size) { sheetOpen = true }
            }
            if (current.isTapToAdvance) {
                PrimaryAction(label = "继续") { onSend("") }
            }
            if (current.needsTextValue) {
                DockTextInput(
                    numericOnly = current.inputType == "IntValue",
                    onSend = onSend,
                )
            }
        }
    }

    if (sheetOpen) {
        EraButtonSheet(
            items = ordered,
            onPick = { item ->
                sheetOpen = false
                onButton(item.button)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun DockHeader(
    awaiting: TurnAwaiting?,
    count: Int,
) {
    val label =
        when {
            awaiting == null -> "连接中"
            awaiting.isEnded -> "本局结束"
            awaiting.inputType == "Void" || !awaiting.acceptsInput -> "推进中"
            else -> "行动"
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "◈", color = TavernPalette.GoldDim, fontSize = 11.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = TavernPalette.TextTertiary,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (count > 0) {
            Text(
                text = count.toString(),
                color = TavernPalette.TextTertiary,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DockHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(text = text, color = TavernPalette.TextTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun EndedPanel(
    awaiting: TurnAwaiting?,
    onRestart: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (awaiting?.endedTerminal == "Quit") "游戏已结束" else "本局已结束",
            color = TavernPalette.TextSecondary,
            fontSize = 13.sp,
        )
        PrimaryAction(label = "重新开始", onClick = onRestart)
    }
}

@Composable
private fun DockActionButton(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x0AFFFFFF))),
                )
                .border(1.dp, TavernPalette.Border, RoundedCornerShape(13.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = TavernPalette.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MoreButton(
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(TavernShape.Pill)
                .background(TavernPalette.Glass)
                .border(1.dp, TavernPalette.Border, TavernShape.Pill)
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "更多 · $count",
            color = TavernPalette.TextSecondary,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(TavernPalette.Fab, TavernPalette.PurpleDeep)))
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DockTextInput(
    numericOnly: Boolean,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TavernPalette.Glass)
                    .border(1.dp, TavernPalette.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    text = if (numericOnly) "输入数字" else "输入指令",
                    color = TavernPalette.TextTertiary,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = TavernPalette.TextPrimary, fontSize = 13.sp),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (numericOnly) KeyboardType.Number else KeyboardType.Text,
                    ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        PrimaryActionSmall(
            label = "发送",
            onClick = {
                onSend(text)
                text = ""
            },
        )
    }
}

@Composable
private fun PrimaryActionSmall(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(TavernPalette.Fab, TavernPalette.PurpleDeep)))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 13.sp)
    }
}

/** 全部按钮的抽屉：按值段分组，横屏从右侧滑出，仍是一屏可见。 */
@Composable
internal fun EraButtonSheet(
    items: List<TurnButtonItem>,
    onPick: (TurnButtonItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val grouped = remember(items) { groupButtons(items) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x8C000000)),
        ) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .widthIn(min = 340.dp, max = 420.dp)
                        .background(TavernPalette.Surface)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "全部动作",
                        color = TavernPalette.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "关闭",
                        color = TavernPalette.TextTertiary,
                        fontSize = 12.sp,
                        modifier =
                            Modifier
                                .clip(TavernShape.Pill)
                                .background(TavernPalette.Glass)
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    GROUP_ORDER.forEach { group ->
                        val groupItems = grouped[group].orEmpty()
                        if (groupItems.isEmpty()) return@forEach
                        item(key = "h-$group") { SheetSectionTitle(group = group, count = groupItems.size) }
                        items(items = groupItems, key = { "i-${it.button.value}" }) { item ->
                            SheetActionRow(label = item.label) { onPick(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSectionTitle(
    group: String,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(3.dp).height(11.dp).clip(RoundedCornerShape(2.dp)).background(TavernPalette.GoldDim),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = group, color = TavernPalette.Gold, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = count.toString(),
            color = TavernPalette.TextTertiary,
            fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

@Composable
private fun SheetActionRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x0AFFFFFF))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TavernPalette.TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 热按钮排前面（返回、移动、对话…），其余按引擎给的原顺序。 */
internal fun orderForDock(items: List<TurnButtonItem>): List<TurnButtonItem> {
    if (items.isEmpty()) return emptyList()
    val byLabel = LinkedHashMap<String, TurnButtonItem>()
    for (item in items) byLabel.putIfAbsent(item.label.trim(), item)
    val result = ArrayList<TurnButtonItem>(items.size)
    for (hot in HOT_LABELS) {
        val hit = byLabel[hot] ?: continue
        result.add(hit)
    }
    for (item in items) {
        if (result.none { it.button.value == item.button.value }) result.add(item)
    }
    return result
}

internal fun groupButtons(items: List<TurnButtonItem>): Map<String, List<TurnButtonItem>> {
    val grouped = LinkedHashMap<String, MutableList<TurnButtonItem>>()
    for (group in GROUP_ORDER) grouped[group] = mutableListOf()
    for (item in items) {
        grouped.getValue(groupOf(item)).add(item)
    }
    return grouped.filterValues { it.isNotEmpty() }
}

private fun groupOf(item: TurnButtonItem): String {
    val button = item.button
    if (!button.isInteger) return "其他"
    val value = button.value.toLongOrNull() ?: return "其他"
    return when {
        value < 100L -> "常用"
        value < 200L -> "场所"
        value < 300L -> "剧情"
        value < 400L -> "档案"
        value < 500L -> "设定"
        value < 600L -> "调试"
        value < 700L -> "杂项"
        value < 800L -> "系统"
        else -> "其他"
    }
}
