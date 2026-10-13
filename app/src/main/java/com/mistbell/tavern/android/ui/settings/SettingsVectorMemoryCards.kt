package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.vector.VectorCacheStatistics
import java.util.Locale

private const val HIT_RATE_PERCENT_MULTIPLIER = 100

/** S2 向量记忆召回设置：记忆源（自动/API/本地 ONNX）+ 召回条数 + 相似度阈值 */
@Suppress("FunctionNaming")
@Composable
internal fun VectorMemorySettingsCard(viewModel: SettingsViewModel) {
    val embeddingSource by viewModel.embeddingSource.collectAsState()
    val recallTopK by viewModel.memoryRecallTopK.collectAsState()
    val threshold by viewModel.memorySimilarityThreshold.collectAsState()
    val cacheStats by viewModel.vectorCacheStatistics.collectAsState()
    val cacheHitRatePercent =
        String.format(Locale.US, "%.1f", cacheStats.hitRate * HIT_RATE_PERCENT_MULTIPLIER)

    SettingsCard(title = "向量记忆") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "向量记忆（语义召回）",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            EmbeddingSourceChips(
                embeddingSource = embeddingSource,
                onSelect = { viewModel.setEmbeddingSource(it) },
            )

            LabeledSlider(
                label = "召回条数：$recallTopK",
                value = recallTopK.toFloat(),
                valueRange = 1f..20f,
                steps = 18,
                onValueChange = { viewModel.setMemoryRecallTopK(it.toInt()) },
            )

            LabeledSlider(
                label = "相似度阈值：${(threshold * 100).toInt()}%",
                value = threshold,
                valueRange = 0.05f..0.95f,
                steps = 17,
                onValueChange = { viewModel.setMemorySimilarityThreshold(it) },
            )

            VectorCacheStatisticsSection(cacheStats, cacheHitRatePercent)

            Text(
                text =
                    "「本地 ONNX」（bge-small-zh，约 24MB 模型随应用内置）无需 API Key 即可使用语义记忆；" +
                        "切换记忆源后请重启应用使新源生效；不同维度的旧向量会自动跳过，不参与本次检索。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun VectorCacheStatisticsSection(
    cacheStats: VectorCacheStatistics,
    cacheHitRatePercent: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "搜索缓存统计",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "命中率：$cacheHitRatePercent%",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                "命中 ${cacheStats.hitCount} 次 · 未命中 ${cacheStats.missCount} 次 · " +
                    "查询 ${cacheStats.totalLookups} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                "缓存条目 ${cacheStats.entryCount} · 写入 ${cacheStats.writeCount} 次 · " +
                    "失效 ${cacheStats.invalidationCount} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "命中率 = 命中数 ÷（命中数 + 未命中数）；写入与失效不计入分母。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "此处只统计向量搜索缓存，不统计聊天 API 请求或词法记忆召回。长期记忆关闭或 Embedding 不可用时，向量查询数会保持为 0。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "统计在应用进程存活期间累计，重启应用后重新计数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun EmbeddingSourceChips(
    embeddingSource: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "记忆源",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = embeddingSource == "auto",
                onClick = { onSelect("auto") },
                label = { Text("自动") },
            )
            FilterChip(
                selected = embeddingSource == "api",
                onClick = { onSelect("api") },
                label = { Text("API") },
            )
            FilterChip(
                selected = embeddingSource == "local",
                onClick = { onSelect("local") },
                label = { Text("本地 ONNX（实验）") },
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
