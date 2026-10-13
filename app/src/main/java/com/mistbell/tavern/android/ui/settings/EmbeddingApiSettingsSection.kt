package com.mistbell.tavern.android.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
@Suppress("FunctionNaming", "LongMethod", "MaxLineLength")
@Composable
fun EmbeddingApiSettingsSection() {
    val context = LocalContext.current
    val prefs = remember(context) {
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
                    prefs.edit()
                        .putString("embedding_api_key", SecureStore.wrap(apiKey.trim()))
                        .putString("embedding_base_url", normalizedUrl)
                        .apply()
                    baseUrl = normalizedUrl
                    status = "配置已保存，正在刷新记忆服务…"
                    scope.launch {
                        val source = runCatching {
                            TavernApplication.instance.container.database.settingsDao()
                                .getValue("embedding_source") ?: "auto"
                        }.getOrDefault("auto")
                        val active = runCatching {
                            TavernApplication.instance.container.refreshEmbeddingSource(source)
                        }.getOrDefault(false)
                        status = when {
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
