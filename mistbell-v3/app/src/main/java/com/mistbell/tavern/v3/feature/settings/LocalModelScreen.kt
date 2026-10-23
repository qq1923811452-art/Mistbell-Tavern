package com.mistbell.tavern.v3.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus

/** Compact page for the on-device embedding model and NPU diagnostics. */
@Suppress("FunctionNaming", "LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelScreen(
    inferenceStatus: InferenceRuntimeStatus,
    operationMessage: String,
    operationBusy: Boolean,
    qualcommModelSelected: Boolean,
    selectedEmbeddingDimensions: Int,
    onSelectModelProfile: (Boolean) -> Unit,
    onCycleEmbeddingDimensions: () -> Unit,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    onRunEmbeddingTest: () -> Unit,
    onRunNpuDiagnosticProbe: () -> Unit,
    onRunAlternateBackendBenchmark: () -> Unit,
    onExportNpuEvidence: () -> Unit,
    onExportLogs: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型与 NPU") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(inferenceStatus.toString(), style = MaterialTheme.typography.bodySmall)
            if (operationMessage.isNotBlank()) {
                Text(operationMessage, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onSelectModelProfile(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (qualcommModelSelected) "使用通用版" else "✓ 通用版")
            }
            Button(onClick = { onSelectModelProfile(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (qualcommModelSelected) "✓ Qualcomm SM8650 专用版" else "使用 Qualcomm SM8650 专用版")
            }
            Text("向量维度：$selectedEmbeddingDimensions", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onCycleEmbeddingDimensions, modifier = Modifier.fillMaxWidth()) {
                Text("切换向量维度")
            }
            Button(onClick = onDownloadModel, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                Text("下载 / 重新下载 EmbeddingGemma 2")
            }
            Button(onClick = onImportModel, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                Text("从文件导入 .litertlm")
            }
            Button(onClick = onRunEmbeddingTest, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                Text("初始化后执行真实向量测试")
            }
            Button(onClick = onRunNpuDiagnosticProbe, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                Text("运行 NPU 输入长度矩阵")
            }
            Button(onClick = onRunAlternateBackendBenchmark, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                Text("实验：GPU / CPU 基准")
            }
            Button(onClick = onExportNpuEvidence, modifier = Modifier.fillMaxWidth()) {
                Text("导出 NPU 证据")
            }
            Button(onClick = onExportLogs, modifier = Modifier.fillMaxWidth()) {
                Text("导出诊断日志")
            }
        }
    }
}
