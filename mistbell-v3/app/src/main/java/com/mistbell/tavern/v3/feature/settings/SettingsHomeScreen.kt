@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber")

package com.mistbell.tavern.v3.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** Compact settings hub modelled on the 2.x layout: groups of one-line destinations. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun SettingsHomeScreen(
    contextTokens: Int,
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
    onOpenLocalModel: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text("偏好与配置", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))

        SettingsGroup("🔧 AI 连接") {
            ApiSettingsSection(onStatus = {})
            SettingsRow("Embedding API", "本地向量与检索", onOpenEmbedding)
        }
        SettingsGroup("💬 对话设置") {
            MaxOutputTokensRow(maxOutputTokens, onMaxOutputTokensChange)
            ContextLengthRow(contextTokens, onContextTokensChange)
            BackupSettingsSection()
        }
        SettingsGroup("🧠 记忆") {
            SwitchRow("启用记忆整理", "抽取事实；关闭后只保留检索", memoryEnabled, onMemoryEnabledChange)
            SettingsRow("记忆整理 API", "独立端点、模型与密钥", onOpenMemorySettings)
        }
        SettingsGroup("🎨 内容") {
            SettingsRow("角色卡管理", "导入、编辑、删除", onOpenCharacters)
            SettingsRow("世界书管理", "导入、启停、编辑", onOpenWorldBook)
            SettingsRow("图片生成", "独立 API 与本地图片库", onOpenImage)
        }
        SettingsGroup("💾 本地推理") {
            SettingsRow("模型与 NPU", "下载、导入、维度、诊断", onOpenLocalModel)
        }
        SettingsGroup("ℹ️ 关于") {
            SettingsRow("许可与第三方声明", "开源许可、模型条款", onOpenLicenses)
        }
    }
}

@Composable
private fun ContextLengthRow(value: Int, onChange: (Int) -> Unit) {
    val options = listOf(2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 1_000_000)
    val index = options.indexOf(value).takeIf { it >= 0 } ?: 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "默认上下文长度",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTokens(options[index]),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFD8B4FE),
            )
        }
        Text("新会话的默认上下文预算", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(options[it.toInt().coerceIn(0, options.lastIndex)]) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 6.dp),
    )
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xCC140C22)) {
        Column { content() }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.6f))
    }
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "1M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

@Suppress("FunctionNaming")
@Composable
private fun MaxOutputTokensRow(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in 1..200_000
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("最大输出 token", style = MaterialTheme.typography.bodyLarge)
            Text("过小会导致回答被截断", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit)
                text = digits
                digits.toIntOrNull()?.takeIf { it in 1..200_000 }?.let(onChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = invalid,
            supportingText = {
                Text(
                    if (invalid) "范围 1–200000，当前输入不会保存" else "实际生效：$value",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            modifier = Modifier.padding(start = 12.dp).width(150.dp),
        )
    }
}
