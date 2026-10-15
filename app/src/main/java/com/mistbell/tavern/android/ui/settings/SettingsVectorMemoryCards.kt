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

/** S2 向量记忆召回设置：记忆源（自动/API/本地 ONNX）+ 召回条数 + 相似度阈值 */
@Suppress("FunctionNaming")
@Composable
internal fun VectorMemorySettingsCard(viewModel: SettingsViewModel) {
    val embeddingSource by viewModel.embeddingSource.collectAsState()
    val recallTopK by viewModel.memoryRecallTopK.collectAsState()
    val threshold by viewModel.memorySimilarityThreshold.collectAsState()

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

            Text(
                text =
                    "「本地 ONNX」（bge-small-zh，约 24MB 模型随应用内置）无需 API Key 即可使用语义记忆；" +
                        "切换源会立即刷新服务。不同 Embedding 模型的向量会隔离检索，旧模型的历史向量需切回原模型，或后续重建索引。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
