package com.mistbell.tavern.v3.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus

/** Compact page for the on-device embedding model and NPU diagnostics. */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelScreen(
    inferenceStatus: InferenceRuntimeStatus,
    operationMessage: String,
    operationBusy: Boolean,
    qualcommModelSelected: Boolean,
    selectedEmbeddingDimensions: Int,
    allowCustomModel: Boolean,
    onAllowCustomModelChange: (Boolean) -> Unit,
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
            ModelStatusBlock(inferenceStatus)
            if (operationMessage.isNotBlank()) {
                Text(operationMessage, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { onSelectModelProfile(false) },
                enabled = !operationBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (qualcommModelSelected) "使用通用版" else "✓ 通用版")
            }
            Button(
                onClick = { onSelectModelProfile(true) },
                enabled = !operationBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (qualcommModelSelected) "✓ Qualcomm SM8650 专用版" else "使用 Qualcomm SM8650 专用版")
            }
            Text("向量维度：$selectedEmbeddingDimensions", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onCycleEmbeddingDimensions,
                enabled = !operationBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
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
            Button(
                onClick = onRunAlternateBackendBenchmark,
                enabled = !operationBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
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

@Suppress("FunctionNaming", "CyclomaticComplexMethod")
@Composable
private fun ModelStatusBlock(status: InferenceRuntimeStatus) {
    val backend = when (status.backend) {
        InferenceBackend.NPU -> "NPU"
        InferenceBackend.GPU -> "GPU"
        InferenceBackend.CPU -> "CPU"
        InferenceBackend.UNKNOWN -> "未验证"
    }
    val phase = when (status.phase) {
        InferencePhase.NOT_INITIALIZED -> "尚未初始化"
        InferencePhase.LOADING_MODEL -> "正在加载模型"
        InferencePhase.INITIALIZED -> "已初始化，尚未验证真实推理"
        InferencePhase.READY -> "已就绪"
        InferencePhase.RUNNING -> "正在推理"
        InferencePhase.FALLBACK -> "已回退"
        InferencePhase.FAILED -> "初始化/推理失败"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("本地 Embedding / NPU 状态", style = MaterialTheme.typography.titleMedium)
        Text("$phase · 配置后端：$backend", style = MaterialTheme.typography.bodyMedium)
        val evidence = when (status.backendEvidence) {
            BackendEvidence.NOT_AVAILABLE -> "未验证"
            BackendEvidence.CONFIGURED_ONLY -> "仅配置成功，未独立确认硬件调用"
            BackendEvidence.RUNTIME_REPORTED -> "运行时报告已加载并推理"
            BackendEvidence.DEVICE_VERIFIED -> "设备侧证据确认"
        }
        Text("实际验证：$evidence", style = MaterialTheme.typography.bodySmall)
        status.modelId?.let { Text("模型：$it", style = MaterialTheme.typography.bodySmall) }
        status.acceleratorName?.let {
            Text("配置后端：$it", style = MaterialTheme.typography.bodySmall)
        }
        status.lastExecutionMillis?.let {
            Text("最近耗时：$it ms", style = MaterialTheme.typography.bodySmall)
        }
        status.fallbackReason?.let {
            Text("回退原因：$it", style = MaterialTheme.typography.bodySmall)
        }
        status.lastError?.let {
            Text("错误：$it", style = MaterialTheme.typography.bodySmall)
        }
    }
}
