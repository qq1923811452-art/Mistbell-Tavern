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

private data class BuildStage(
    val title: String,
    val detail: String,
)

private val initialStages = listOf(
    BuildStage(
        title = "聊天与 API 核心",
        detail = "尚未实现：先建立独立的请求、流式响应、取消与错误处理链路。",
    ),
    BuildStage(
        title = "世界书与角色卡",
        detail = "尚未实现：定义 Mistbell 3.0 专属格式，并为旧格式编写独立导入器。",
    ),
    BuildStage(
        title = "记忆与本地向量",
        detail = "尚未实现：独立记忆整理 API、事实历史、持久化向量与重建。",
    ),
    BuildStage(
        title = "本地推理与硬件状态",
        detail = "尚未验证：评估 EmbeddingGemma 2 / LiteRT-LM，并报告真实后端与回退原因。",
    ),
    BuildStage(
        title = "生图、安全与备份",
        detail = "尚未实现：独立生图 API、凭据隔离、事务式备份恢复与隐私保护。",
    ),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "从零构建，不复用旧应用实现",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "此页面仅表示新工程已建立。下列功能均未宣称完成；每项都要经过独立实现与验证。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(initialStages, key = BuildStage::title) { stage ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stage.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stage.detail,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
