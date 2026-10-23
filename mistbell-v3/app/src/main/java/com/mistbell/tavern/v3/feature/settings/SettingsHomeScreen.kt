package com.mistbell.tavern.v3.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Compact settings hub: one line per destination, no development prose. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun SettingsHomeScreen(
    onOpenChatSettings: () -> Unit,
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
        Text(
            "偏好与配置",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        SettingsGroup("连接与生成") {
            SettingsRow("聊天 API 与生成", "模型、密钥、流式、备份", onOpenChatSettings)
            SettingsRow("图片生成", "独立 API 与本地图片库", onOpenImage)
        }
        SettingsGroup("内容") {
            SettingsRow("角色卡管理", "导入、编辑、删除", onOpenCharacters)
            SettingsRow("世界书管理", "导入、启停、编辑", onOpenWorldBook)
        }
        SettingsGroup("本地推理") {
            SettingsRow("模型与 NPU", "下载、导入、维度、诊断", onOpenLocalModel)
        }
        SettingsGroup("关于") {
            SettingsRow("许可与第三方声明", "开源许可、模型条款", onOpenLicenses)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 6.dp),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xCC140C22),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.6f))
    }
}
