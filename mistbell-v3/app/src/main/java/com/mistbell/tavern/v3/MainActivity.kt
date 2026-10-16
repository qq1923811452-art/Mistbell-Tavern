package com.mistbell.tavern.v3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus

private data class BuildStage(val title: String, val detail: String)

private val initialStages = listOf(
    BuildStage("聊天与 API 核心", "已建立独立流式聊天客户端与提示词组装基础；端到端集成仍需继续验证。"),
    BuildStage("世界书与角色卡", "按 3.0 专属格式重新实现，并为旧格式编写独立导入器；尚未完成。"),
    BuildStage("记忆与本地向量", "已建立事实历史、批处理策略和向量快照基础；数据库接入、召回与重建仍需继续。"),
    BuildStage("本地推理与硬件状态", "已建立推理运行状态与 Embedding 输出校验契约；EmbeddingGemma 2 / LiteRT-LM 真机后端尚未验证。"),
    BuildStage("生图、安全与备份", "独立生图 API、凭据隔离、事务式备份恢复仍待实现。"),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DevelopmentHome()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevelopmentHome() {
    // This is deliberately an idle status until a real runtime adapter reports execution.
    // The UI must never infer NPU/GPU use from the phone model or a requested delegate.
    val inferenceStatus = InferenceRuntimeStatus.idle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mistbell Tavern 3.0")
                        Text(
                            text = "全新实现 · 开发基线",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("从零构建，不复用旧应用实现", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "这是独立的 3.0 工程。状态只反映已实现与已验证的内容，不把计划或依赖安装视为功能完成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { InferenceStatusCard(inferenceStatus) }
            items(initialStages, key = BuildStage::title) { stage ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stage.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(stage.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun InferenceStatusCard(status: InferenceRuntimeStatus) {
    val backendLabel = when (status.backend) {
        InferenceBackend.NPU -> "NPU"
        InferenceBackend.GPU -> "GPU"
        InferenceBackend.CPU -> "CPU"
        InferenceBackend.UNKNOWN -> "未验证"
    }
    val phaseLabel = when (status.phase) {
        InferencePhase.NOT_INITIALIZED -> "尚未初始化"
        InferencePhase.LOADING_MODEL -> "正在加载模型"
        InferencePhase.READY -> "已就绪"
        InferencePhase.RUNNING -> "正在推理"
        InferencePhase.FALLBACK -> "已回退"
        InferencePhase.FAILED -> "初始化/推理失败"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本地 Embedding / NPU 状态", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("$phaseLabel · 后端：$backendLabel", style = MaterialTheme.typography.bodyMedium)
            val details = listOfNotNull(
                status.modelId?.let { "模型：$it" },
                status.acceleratorName?.let { "设备：$it" },
                status.lastExecutionMillis?.let { "最近耗时：$it ms" },
                status.fallbackReason?.let { "回退原因：$it" },
                status.lastError?.let { "错误：$it" },
            )
            details.forEach { detail ->
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (status.phase == InferencePhase.NOT_INITIALIZED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前没有运行中的推理后端；不会虚报 NPU 已调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
