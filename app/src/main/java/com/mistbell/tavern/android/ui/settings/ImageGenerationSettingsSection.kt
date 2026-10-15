package com.mistbell.tavern.android.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.image.ImageGenerationConfig
import com.mistbell.tavern.android.data.image.ImageGenerationService
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import com.mistbell.tavern.android.util.ApiEndpointPolicy
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val IMAGE_API_BASE_URL = "image_api_base_url"
private const val IMAGE_API_KEY = "image_api_key"
private const val IMAGE_API_MODEL = "image_api_model"
private const val DEFAULT_IMAGE_DIMENSION = 1024
private const val MAX_IMAGE_DIMENSION_DIGITS = 4
private val IMAGE_SETTINGS_ROW_SPACING = 8.dp
private const val IMAGE_SETTINGS_FIELD_WEIGHT = 1f

@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming", "TooGenericExceptionCaught")
@Composable
fun ImageGenerationSettingsSection() {
    val context = LocalContext.current
    val settingsDao = TavernApplication.instance.container.database.settingsDao()
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyUnreadable by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var width by remember { mutableStateOf(DEFAULT_IMAGE_DIMENSION.toString()) }
    var height by remember { mutableStateOf(DEFAULT_IMAGE_DIMENSION.toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var generatedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loaded =
            withContext(Dispatchers.IO) {
                val storedKey = settingsDao.getValue(IMAGE_API_KEY).orEmpty()
                val decryptedKey = SecureStore.unwrapOrNull(storedKey)
                Pair(
                    listOf(
                        settingsDao.getValue(IMAGE_API_BASE_URL).orEmpty(),
                        decryptedKey.orEmpty(),
                        settingsDao.getValue(IMAGE_API_MODEL).orEmpty(),
                    ),
                    decryptedKey == null,
                )
            }
        baseUrl = loaded.first[0]
        apiKey = loaded.first[1]
        model = loaded.first[2]
        apiKeyUnreadable = loaded.second
    }

    ModernSettingsGroup(title = "🖼️ 独立生图 API") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "与聊天模型、Embedding 密钥完全分开。第一阶段支持 OpenAI 兼容的 /images/generations 接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; status = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("生图 API 基础地址") },
                placeholder = { Text("https://example.com/v1") },
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    apiKeyUnreadable = it.isBlank()
                    status = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("独立生图 API Key（可留空）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; status = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("生图模型 ID") },
                placeholder = { Text("由你的服务商提供") },
                singleLine = true,
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it; status = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("图片提示词") },
                placeholder = { Text("描述角色外观、动作、场景、光线与画风") },
                minLines = 3,
                maxLines = 6,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(IMAGE_SETTINGS_ROW_SPACING)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter(Char::isDigit).take(MAX_IMAGE_DIMENSION_DIGITS) },
                    modifier = Modifier.weight(IMAGE_SETTINGS_FIELD_WEIGHT),
                    label = { Text("宽度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter(Char::isDigit).take(MAX_IMAGE_DIMENSION_DIGITS) },
                    modifier = Modifier.weight(IMAGE_SETTINGS_FIELD_WEIGHT),
                    label = { Text("高度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = null
                            try {
                                val endpointError = ApiEndpointPolicy.writeRejectionReason(baseUrl)
                                if (endpointError != null) {
                                    status = endpointError
                                    return@launch
                                }
                                if (apiKeyUnreadable) {
                                    status = "旧生图 API Key 无法解密；请重新输入密钥后再保存，原密文不会被覆盖。"
                                    return@launch
                                }
                                withContext(Dispatchers.IO) {
                                    settingsDao.upsert(SettingsEntity(IMAGE_API_BASE_URL, baseUrl.trim()))
                                    settingsDao.upsert(SettingsEntity(IMAGE_API_KEY, SecureStore.wrap(apiKey.trim())))
                                    settingsDao.upsert(SettingsEntity(IMAGE_API_MODEL, model.trim()))
                                }
                                status = "生图 API 配置已保存"
                            } catch (e: Exception) {
                                status = "保存失败：${e.message ?: "未知错误"}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && !apiKeyUnreadable,
                ) { Text("保存配置") }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "正在请求生图 API…"
                            try {
                                val result =
                                    ImageGenerationService(context).generate(
                                        ImageGenerationConfig(
                                            baseUrl = baseUrl,
                                            apiKey = apiKey,
                                            model = model,
                                            width = width.toIntOrNull() ?: DEFAULT_IMAGE_DIMENSION,
                                            height = height.toIntOrNull() ?: DEFAULT_IMAGE_DIMENSION,
                                        ),
                                        prompt,
                                    )
                                generatedPath = result.file.absolutePath
                                // Treat provider-returned revised prompts as untrusted and potentially sensitive.
                                status = "生成成功"
                            } catch (e: Exception) {
                                status = "生成失败：${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && !apiKeyUnreadable && prompt.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("生成测试图")
                    }
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            generatedPath?.let { path ->
                val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                if (bitmap != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "生图 API 测试结果",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(
                        "测试图已保存在应用私有目录。后续聊天集成会复用这套 API 配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}