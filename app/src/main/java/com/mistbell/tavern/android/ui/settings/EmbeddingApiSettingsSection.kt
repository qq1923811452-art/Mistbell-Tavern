package com.mistbell.tavern.android.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.launch

/** 简单的 Embedding API 配置入口；密钥使用 AndroidKeyStore 加密保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "MaxLineLength")
@Composable
fun EmbeddingApiSettingsSection() {
    val context = LocalContext.current
    val prefs =
        remember(context) {
            context.getSharedPreferences("tavern_settings", Context.MODE_PRIVATE)
        }
    val scope = rememberCoroutineScope()
    var apiKey by remember {
        mutableStateOf(SecureStore.unwrap(prefs.getString("embedding_api_key", "") ?: ""))
    }
    var baseUrl by remember {
        mutableStateOf(
            prefs.getString("embedding_base_url", "https://api.openai.com/v1")
                ?: "https://api.openai.com/v1",
        )
    }
    val presetModels = listOf("text-embedding-3-small", "text-embedding-ada-002")
    val storedModel = remember(context) { prefs.getString("embedding_model", "text-embedding-3-small") ?: "text-embedding-3-small" }
    var selectedModel by remember { mutableStateOf(if (storedModel in presetModels) storedModel else "自定义模型 ID") }
    var customModel by remember { mutableStateOf(if (storedModel in presetModels) "" else storedModel) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    ModernSettingsGroup(title = "🧠 Embedding API") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "用于向量长期记忆。可填写兼容 OpenAI Embeddings 接口的服务地址和密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Embedding API 地址") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
            )
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    label = { Text("Embedding 模型") },
                    trailingIcon = {
                        androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                    },
                    singleLine = true,
                )
                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    (presetModels + "自定义模型 ID").forEach { modelOption ->
                        DropdownMenuItem(
                            text = { Text(modelOption) },
                            onClick = {
                                selectedModel = modelOption
                                modelMenuExpanded = false
                            },
                        )
                    }
                }
            }
            if (selectedModel == "自定义模型 ID") {
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { customModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义模型 ID") },
                    placeholder = { Text("填写服务商支持的模型名称") },
                    singleLine = true,
                )
            }
            Text(
                text = "提示：当前向量记忆索引按 1536 维构建；自定义模型必须返回 1536 维向量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Embedding API 密钥") },
                placeholder = { Text("输入专用的 Embedding API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = {
                    val normalizedUrl =
                        baseUrl.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
                    val normalizedModel = (if (selectedModel == "自定义模型 ID") customModel else selectedModel).trim()
                    prefs.edit()
                        .putString("embedding_api_key", SecureStore.wrap(apiKey.trim()))
                        .putString("embedding_base_url", normalizedUrl)
                        .putString("embedding_model", normalizedModel.ifBlank { "text-embedding-3-small" })
                        .apply()
                    baseUrl = normalizedUrl
                    status = "配置已保存，正在刷新记忆服务…"
                    scope.launch {
                        val source =
                            runCatching {
                                TavernApplication.instance.container.database.settingsDao()
                                    .getValue("embedding_source") ?: "auto"
                            }.getOrDefault("auto")
                        val active =
                            runCatching {
                                TavernApplication.instance.container.refreshEmbeddingSource(source)
                            }.getOrDefault(false)
                        status =
                            when {
                                active -> "已保存并立即生效，Embedding 服务已启用。"
                                apiKey.isBlank() -> "已保存。密钥为空，向量记忆 API 暂不可用。"
                                else -> "已保存，但服务未能启用；请检查 API 地址、密钥和模型兼容性。"
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并立即生效")
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
