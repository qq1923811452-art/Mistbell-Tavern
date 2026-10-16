package com.mistbell.tavern.v3.feature.chat

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet

private data class ChatLine(val role: String, val content: String)

/**
 * Minimal end-to-end chat screen for 3.0 development.
 * Provider settings and the API key remain in screen memory only; they are not persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit) {
    var baseUrl by remember { mutableStateOf("https://api.odys.work/v1") }
    var modelId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("填写模型 ID 和 API 配置后即可测试聊天。") }
    val history = remember { mutableStateListOf<ChatLine>() }
    val listState = rememberLazyListState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mistbell Tavern · 聊天测试") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("返回首页") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("聊天 API 配置", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("API Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text("模型 ID（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key（远程接口必填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        "当前为开发测试入口：API 配置不会持久保存；离开此页面后密钥会从界面状态中清除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(status, style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(history) { _, line ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                if (line.role == "user") "你" else "Mistbell",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(line.content.ifBlank { if (busy && line.role == "assistant") "正在生成……" else "" })
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("输入消息") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                minLines = 2,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val userText = draft.trim()
                        if (userText.isEmpty() || busy) return@Button
                        if (modelId.isBlank()) {
                            status = "请先填写模型 ID。"
                            return@Button
                        }
                        val profile = ProviderProfile(
                            id = "session-chat",
                            kind = ProviderKind.CHAT,
                            baseUrl = baseUrl.trim(),
                            modelId = modelId.trim(),
                            credentialHandle = if (apiKey.isBlank()) null else "session-chat-key",
                        )
                        val providers = try {
                            ProviderProfileSet(
                                profiles = listOf(profile),
                                selectedProfileIds = mapOf(ProviderKind.CHAT to profile.id),
                            )
                        } catch (_: IllegalArgumentException) {
                            status = "API 配置无效，请检查 Base URL 和模型 ID。"
                            return@Button
                        }
                        val secretForThisRequest = apiKey
                        val client = OpenAiCompatibleChatClient(
                            providers = providers,
                            credentials = CredentialResolver { handle ->
                                if (handle == "session-chat-key") secretForThisRequest else null
                            },
                        )
                        history += ChatLine("user", userText)
                        history += ChatLine("assistant", "")
                        draft = ""
                        busy = true
                        status = "正在连接聊天 API……"
                        val messages = buildList {
                            add(PromptMessage(PromptRole.SYSTEM, "你是 Mistbell Tavern 的聊天助手。请直接、清晰地回答用户。"))
                            history.dropLast(2).forEach { line ->
                                if (line.content.isNotBlank()) {
                                    add(
                                        PromptMessage(
                                            if (line.role == "user") PromptRole.USER else PromptRole.ASSISTANT,
                                            line.content,
                                        ),
                                    )
                                }
                            }
                            add(PromptMessage(PromptRole.USER, userText))
                        }
                        try {
                            client.stream(messages, object : ChatStreamObserver {
                                override fun onDelta(text: String) {
                                    mainHandler.post {
                                        val last = history.lastOrNull()
                                        if (last?.role == "assistant") {
                                            history[history.lastIndex] = last.copy(content = last.content + text)
                                        }
                                    }
                                }

                                override fun onComplete() {
                                    mainHandler.post {
                                        busy = false
                                        status = "回复完成。"
                                    }
                                }

                                override fun onFailure(failure: ChatStreamFailure) {
                                    mainHandler.post {
                                        busy = false
                                        status = failure.safeMessage
                                        val last = history.lastOrNull()
                                        if (last?.role == "assistant" && last.content.isBlank()) {
                                            history[history.lastIndex] = last.copy(content = failure.safeMessage)
                                        }
                                    }
                                }
                            })
                        } catch (error: Exception) {
                            busy = false
                            status = error.message?.take(180) ?: "聊天请求配置失败。"
                            val last = history.lastOrNull()
                            if (last?.role == "assistant" && last.content.isBlank()) {
                                history[history.lastIndex] = last.copy(content = status)
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "生成中…" else "发送消息") }
                Button(
                    onClick = {
                        history.clear()
                        status = "会话已清空。"
                    },
                    enabled = !busy && history.isNotEmpty(),
                ) { Text("清空会话") }
            }
        }
    }
}
