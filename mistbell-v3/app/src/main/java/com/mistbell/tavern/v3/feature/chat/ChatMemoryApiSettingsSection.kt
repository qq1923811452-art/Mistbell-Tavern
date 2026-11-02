@file:Suppress(
    "FunctionNaming",
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "TooGenericExceptionCaught",
)

package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.feature.memory.MemoryCredentialResolver
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionClient
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionConnectionProbe
import com.mistbell.tavern.v3.feature.memory.MemoryModelCatalogClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun ChatMemoryApiSettingsSection(
    settings: SharedPreferences,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    hasSavedCredential: Boolean,
    credentialStore: AndroidKeystoreMemoryCredentialStore,
    diagnosticStore: DiagnosticLogStore,
    onBaseUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSavedCredentialChange: (Boolean) -> Unit,
    onStatusChange: (String) -> Unit,
    extractionEnabled: Boolean,
    onExtractionEnabledChange: (Boolean) -> Unit,
) {
    val credentialCoordinator =
        ChatMemoryCredentialCoordinator(
            settings = settings,
            credentialStore = credentialStore,
            diagnosticStore = diagnosticStore,
        )
    val scope = rememberCoroutineScope()
    var testingConnection by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("独立记忆整理 API（可选）", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用记忆整理", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭后不再自动抽取事实，已有记忆仍会参与检索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = extractionEnabled, onCheckedChange = onExtractionEnabledChange)
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    settings.edit().putString("memory_api_base_url", it).apply()
                    onBaseUrlChange(it)
                },
                label = { Text("记忆整理 API Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = {
                    settings.edit().putString("memory_model_id", it).apply()
                    onModelIdChange(it)
                },
                label = { Text("记忆整理模型 ID（留空则关闭）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("独立记忆 API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val result = credentialCoordinator.save(apiKey)
                    onStatusChange(result.statusMessage)
                    if (result.success) {
                        onApiKeyChange("")
                        onSavedCredentialChange(true)
                    }
                }) {
                    Text("安全保存密钥")
                }
                Button(
                    onClick = {
                        val result = credentialCoordinator.deleteSaved()
                        onStatusChange(result.statusMessage)
                        if (result.success) onSavedCredentialChange(false)
                    },
                    enabled = hasSavedCredential,
                ) {
                    Text("删除已保存密钥")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (testingConnection) return@Button
                        val endpoint = baseUrl.trim()
                        val model = modelId.trim()
                        if (endpoint.isBlank() || model.isBlank()) {
                            onStatusChange("测试连接前请先填写 Base URL 和记忆整理模型 ID。")
                            return@Button
                        }
                        val savedHandle = settings.getString("memory_api_credential_handle", null)
                        val probeHandle =
                            when {
                                apiKey.isNotBlank() -> INLINE_MEMORY_PROBE_HANDLE
                                hasSavedCredential -> savedHandle
                                else -> null
                            }
                        testingConnection = true
                        onStatusChange("正在测试记忆整理 API（固定示例文本，30 秒超时，不写入记忆）……")
                        scope.launch {
                            val result =
                                try {
                                    val profile =
                                        ProviderProfile(
                                            id = "memory-connection-probe",
                                            kind = ProviderKind.MEMORY_EXTRACTION,
                                            baseUrl = endpoint,
                                            modelId = model,
                                            credentialHandle = probeHandle,
                                        )
                                    val providers =
                                        ProviderProfileSet(
                                            profiles = listOf(profile),
                                            selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
                                        )
                                    val resolver =
                                        MemoryCredentialResolver { requested ->
                                            if (requested == INLINE_MEMORY_PROBE_HANDLE) {
                                                apiKey
                                            } else {
                                                credentialStore.resolve(requested)
                                            }
                                        }
                                    val client =
                                        MemoryExtractionClient(
                                            providers = providers,
                                            credentials = resolver,
                                            onDiagnostic = { event, detail ->
                                                diagnosticStore.info(event, "probe=true; $detail")
                                            },
                                        )
                                    withTimeout(PROBE_TIMEOUT_MILLIS) {
                                        MemoryExtractionConnectionProbe.run(client)
                                    }
                                } catch (_: TimeoutCancellationException) {
                                    MemoryExtractionConnectionProbe.Result(
                                        success = false,
                                        detail = "连接超时（30 秒）。请检查 Base URL、网络和模型是否可用。",
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    MemoryExtractionConnectionProbe.Result(
                                        success = false,
                                        detail =
                                            "连接失败：${error::class.java.simpleName}: " +
                                                (error.message ?: "unknown").take(ERROR_PREVIEW_CHARS),
                                    )
                                } finally {
                                    testingConnection = false
                                }
                            onStatusChange(result.detail)
                        }
                    },
                    enabled = !testingConnection && !fetchingModels,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (testingConnection) "测试中…" else "测试连接")
                }
                Button(
                    onClick = {
                        if (fetchingModels) return@Button
                        val endpoint = baseUrl.trim()
                        if (endpoint.isBlank()) {
                            onStatusChange("获取模型前请先填写 Base URL。")
                            return@Button
                        }
                        fetchingModels = true
                        onStatusChange("正在获取记忆整理模型列表……")
                        scope.launch {
                            val models =
                                try {
                                    val key =
                                        withContext(Dispatchers.IO) {
                                            when {
                                                apiKey.isNotBlank() -> apiKey
                                                hasSavedCredential ->
                                                    settings
                                                        .getString("memory_api_credential_handle", null)
                                                        ?.let { credentialStore.resolve(it) }
                                                else -> null
                                            }
                                        }
                                    withTimeout(MODEL_LIST_TIMEOUT_MILLIS) {
                                        MemoryModelCatalogClient().fetch(endpoint, key) { event, detail ->
                                            diagnosticStore.info(event, "probe=true; $detail")
                                        }
                                    }
                                } catch (_: TimeoutCancellationException) {
                                    onStatusChange("获取模型超时（20 秒）。请检查网络或手动填写模型 ID。")
                                    emptyList()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    onStatusChange(
                                        "获取模型失败：${error::class.java.simpleName}: " +
                                            (error.message ?: "unknown").take(ERROR_PREVIEW_CHARS),
                                    )
                                    emptyList()
                                } finally {
                                    fetchingModels = false
                                }
                            if (models.isNotEmpty()) {
                                availableModels = models
                                showModelPicker = true
                                onStatusChange("获取到 ${models.size} 个模型，请在列表中选择。")
                            }
                        }
                    },
                    enabled = !fetchingModels && !testingConnection,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (fetchingModels) "获取中…" else "获取模型")
                }
            }
            Text(
                "与聊天 API 分开配置。Base URL 和模型 ID 保存在本机；密钥不会写入 SharedPreferences 或诊断日志。每累计 3 轮已完成对话后异步整理，失败不会撤销聊天回复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (hasSavedCredential) "已保存加密密钥；不会显示或回填明文。" else "尚未保存记忆 API 密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("选择记忆整理模型") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(availableModels, key = { it }) { model ->
                        TextButton(
                            onClick = {
                                settings.edit().putString("memory_model_id", model).apply()
                                onModelIdChange(model)
                                showModelPicker = false
                                onStatusChange("已选择记忆整理模型：$model")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(model)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) { Text("取消") }
            },
        )
    }
}

private const val INLINE_MEMORY_PROBE_HANDLE = "memory-connection-probe"
private const val PROBE_TIMEOUT_MILLIS = 30_000L
private const val MODEL_LIST_TIMEOUT_MILLIS = 20_000L
private const val ERROR_PREVIEW_CHARS = 160
