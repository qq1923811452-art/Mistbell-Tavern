@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "MagicNumber")

package com.mistbell.tavern.v3.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.ui.tavern.TavernGlassCard
import com.mistbell.tavern.v3.ui.tavern.TavernNavRow
import com.mistbell.tavern.v3.ui.tavern.TavernPageHeader
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernSectionLabel
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import com.mistbell.tavern.v3.ui.tavern.rememberTavernScrollState

private const val SWITCH_TRACK_ALPHA = 0.45f
private const val TEXT_FIELD_WIDTH = 150

/**
 * Settings hub rebuilt on the night-tavern glass surfaces: a serif page header, one glass card per
 * group, and hairline separators instead of shaded boxes.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun SettingsHomeScreen(
    contextTokens: Int,
    recentTurns: Int,
    summaryMaxChars: Int,
    worldbookMaxChars: Int,
    onWorldbookMaxCharsChange: (Int) -> Unit,
    onRecentTurnsChange: (Int) -> Unit,
    onSummaryMaxCharsChange: (Int) -> Unit,
    memoryEnabled: Boolean,
    onContextTokensChange: (Int) -> Unit,
    onMemoryEnabledChange: (Boolean) -> Unit,
    maxOutputTokens: Int,
    onMaxOutputTokensChange: (Int) -> Unit,
    onOpenEmbedding: () -> Unit,
    onOpenMemorySettings: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenWorldBook: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberTavernScrollState("settings"))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TavernPageHeader(
            title = "设置",
            subtitle = "偏好与配置",
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        SettingsGroup("AI 连接") {
            ApiSettingsSection(onStatus = {})
            SettingsDivider()
            SettingsRow("Embedding API", "本地向量、模型与 NPU 诊断", onOpenEmbedding)
        }
        SettingsGroup("对话设置") {
            MaxOutputTokensRow(maxOutputTokens, onMaxOutputTokensChange)
            SettingsDivider()
            ContextLengthRow(contextTokens, onContextTokensChange)
            SettingsDivider()
            NumberSettingRow(
                title = "历史原文轮数目标",
                subtitle = "含固定开头与近期历史；整块裁剪可短暂超出；1–100",
                value = recentTurns,
                range = 1..100,
                onChange = onRecentTurnsChange,
            )
            SettingsDivider()
            NumberSettingRow(
                title = "摘要字符上限",
                subtitle = "历史摘要的 UTF-16 软预算；0 表示不生成摘要",
                value = summaryMaxChars,
                range = 0..10_000,
                onChange = onSummaryMaxCharsChange,
            )
            SettingsDivider()
            NumberSettingRow(
                title = "世界书字符预算（非 token）",
                subtitle = "激活世界书条目的 UTF-16 字符上限；4000–64000",
                value = worldbookMaxChars,
                range = 4_000..64_000,
                onChange = onWorldbookMaxCharsChange,
            )
            SettingsDivider()
            BackupSettingsSection()
        }
        SettingsGroup("记忆") {
            SwitchRow("启用记忆整理", "抽取事实；关闭后只保留检索", memoryEnabled, onMemoryEnabledChange)
            SettingsDivider()
            SettingsRow("记忆整理 API", "独立端点、模型与密钥", onOpenMemorySettings)
        }
        SettingsGroup("内容") {
            SettingsRow("角色卡管理", "导入、编辑、删除", onOpenCharacters)
            SettingsDivider()
            SettingsRow("世界书管理", "导入、启停、编辑", onOpenWorldBook)
            SettingsDivider()
            SettingsRow("图片生成", "独立 API 与本地图片库", onOpenImage)
        }
        SettingsGroup("关于") {
            SettingsRow("许可与第三方声明", "开源许可、模型条款", onOpenLicenses)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ContextLengthRow(
    value: Int,
    onChange: (Int) -> Unit,
) {
    val options = listOf(2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 1_000_000)
    val index = options.indexOf(value).takeIf { it >= 0 } ?: 1
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = TavernPalette.Purple,
            activeTrackColor = TavernPalette.Purple,
            inactiveTrackColor = TavernPalette.Border,
        )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "默认上下文长度",
                style = MaterialTheme.typography.bodyLarge,
                color = TavernPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTokens(options[index]),
                style = MaterialTheme.typography.titleMedium,
                color = TavernPalette.PurpleSoft,
            )
        }
        Text(
            text = "新会话的默认上下文预算",
            style = MaterialTheme.typography.bodySmall,
            color = TavernPalette.TextTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(options[it.toInt().coerceIn(0, options.lastIndex)]) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            colors = sliderColors,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val switchColors =
        SwitchDefaults.colors(
            checkedThumbColor = TavernPalette.Purple,
            checkedTrackColor = TavernPalette.Purple.copy(alpha = SWITCH_TRACK_ALPHA),
            uncheckedThumbColor = TavernPalette.TextTertiary,
            uncheckedTrackColor = TavernPalette.Border,
        )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TavernPalette.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TavernPalette.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = switchColors,
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        TavernSectionLabel(text = title)
        Spacer(Modifier.height(8.dp))
        TavernGlassCard(Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(TavernPalette.Border))
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    TavernNavRow(title = title, subtitle = subtitle, onClick = onClick)
}

private fun formatTokens(tokens: Int): String =
    when {
        tokens >= 1_000_000 -> "1M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }

@Suppress("FunctionNaming")
@Composable
private fun NumberSettingRow(
    title: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid =
        text.length !in 1..6 || text.any { it !in '0'..'9' } || parsed == null || parsed !in range
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TavernPalette.Purple,
            unfocusedBorderColor = TavernPalette.Border,
            focusedTextColor = TavernPalette.TextPrimary,
            unfocusedTextColor = TavernPalette.TextPrimary,
            cursorColor = TavernPalette.Purple,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TavernPalette.TextPrimary)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TavernPalette.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                if (raw.all { it in '0'..'9' }) {
                    raw.toIntOrNull()?.takeIf { it in range }?.let(onChange)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = invalid,
            supportingText = {
                Text(
                    text = if (invalid) "请输入 ${range.first}–${range.last}，当前输入不会保存" else "实际生效：$value",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (invalid) TavernPalette.Danger else TavernPalette.TextTertiary,
                )
            },
            colors = fieldColors,
            shape = TavernShape.CardSmall,
            modifier = Modifier.padding(start = 12.dp).width(TEXT_FIELD_WIDTH.dp),
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MaxOutputTokensRow(
    value: Int,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid =
        text.length !in 1..6 || text.any { it !in '0'..'9' } || parsed == null || parsed !in 1..200_000
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TavernPalette.Purple,
            unfocusedBorderColor = TavernPalette.Border,
            focusedTextColor = TavernPalette.TextPrimary,
            unfocusedTextColor = TavernPalette.TextPrimary,
            cursorColor = TavernPalette.Purple,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("最大输出 token", style = MaterialTheme.typography.bodyLarge, color = TavernPalette.TextPrimary)
            Text(
                text = "过小会导致回答被截断",
                style = MaterialTheme.typography.bodySmall,
                color = TavernPalette.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                if (raw.all { it in '0'..'9' }) {
                    raw.toIntOrNull()?.takeIf { it in 1..200_000 }?.let(onChange)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = invalid,
            supportingText = {
                Text(
                    text = if (invalid) "请输入纯数字 1–200000，当前输入不会保存" else "实际生效：$value",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (invalid) TavernPalette.Danger else TavernPalette.TextTertiary,
                )
            },
            colors = fieldColors,
            shape = TavernShape.CardSmall,
            modifier = Modifier.padding(start = 12.dp).width(TEXT_FIELD_WIDTH.dp),
        )
    }
}
