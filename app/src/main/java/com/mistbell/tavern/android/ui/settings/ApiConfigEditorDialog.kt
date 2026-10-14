package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mistbell.tavern.android.data.api.ApiTestResult
import com.mistbell.tavern.android.data.api.ApiType
import com.mistbell.tavern.android.data.api.defaultBaseUrlForType

/** 接口类型选项（value → 显示名） */
private val API_TYPE_OPTIONS =
    listOf(
        ApiType.OPENAI to "OpenAI 兼容",
        ApiType.ANTHROPIC to "Anthropic",
        ApiType.GEMINI to "Gemini",
        ApiType.CUSTOM to "自定义接口",
    )

// 获取模型后展示的可选芯片数量上限
private const val FETCHED_MODELS_DISPLAY_LIMIT = 12

private fun apiTypeLabel(type: String): String = API_TYPE_OPTIONS.find { it.first == type }?.second ?: type

/**
 * 选中类型后的预设地址回填：用户未填写，或当前值仍是另一类型的预设值时才覆盖。
 * 纯函数（detekt 复杂度独立于调用方）。
 */
private fun resolvePresetUrl(
    type: String,
    current: String,
): String {
    val preset = defaultBaseUrlForType(type)
    return if (preset.isNotBlank() &&
        (current.isBlank() || API_TYPE_OPTIONS.any { defaultBaseUrlForType(it.first) == current })
    ) {
        preset
    } else {
        current
    }
}

/** 标题 + 说明 + 开关的设置行（编辑对话框内复用） */
@Suppress("FunctionNaming")
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/** API 密钥输入（带显示/隐藏切换） */
@Suppress("FunctionNaming")
@Composable
private fun ApiKeyField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("API 密钥") },
        placeholder = { Text("sk-...") },
        leadingIcon = {
            Icon(Icons.Default.Key, null)
        },
        trailingIcon = {
            IconButton(onClick = { showApiKey = !showApiKey }) {
                Icon(
                    imageVector = if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (showApiKey) "隐藏" else "显示",
                )
            }
        },
        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
}

/** 编辑对话框底部：取消 + 确认按钮（表单完整性的判定收在内部） */
@Suppress("FunctionNaming", "LongParameterList") // 表单字段直传
@Composable
private fun DialogConfirmButtons(
    isAdd: Boolean,
    name: String,
    apiUrl: String,
    apiKey: String,
    model: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val canConfirm =
        name.isNotBlank() &&
            ApiBaseUrlPolicy.isValid(apiUrl) &&
            !ApiBaseUrlPolicy.requiresCleartextWarning(apiUrl) &&
            apiKey.isNotBlank() &&
            model.isNotBlank()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("取消")
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            enabled = canConfirm,
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isAdd) "添加" else "保存")
        }
    }
}

/**
 * 模型输入 + 获取模型按钮 + 拉取结果（点击芯片填入模型名）
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // 表单字段直传的聚合区块
@Composable
private fun ApiModelField(
    model: String,
    onModelChange: (String) -> Unit,
    apiUrl: String,
    apiKey: String,
    type: String,
    viewModel: ApiConfigViewModel?,
    fetchedModels: List<String>,
    fetchingModels: Boolean,
    fetchModelsError: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("模型") },
                placeholder = { Text("gpt-4o") },
                leadingIcon = {
                    Icon(Icons.Default.SmartToy, null)
                },
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedButton(
                onClick = {
                    viewModel?.fetchModels(apiUrl, apiKey, type)
                },
                enabled = viewModel != null && apiUrl.isNotBlank() && apiKey.isNotBlank() && !fetchingModels,
                shape = RoundedCornerShape(16.dp),
            ) {
                if (fetchingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("获取模型")
            }
        }
        if (fetchedModels.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fetchedModels.take(FETCHED_MODELS_DISPLAY_LIMIT).forEach { modelName ->
                    FilterChip(
                        selected = model == modelName,
                        onClick = { onModelChange(modelName) },
                        label = { Text(modelName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        fetchModelsError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 编辑对话框内的连接测试（行内结果展示，不落库）
 */
@Suppress("FunctionNaming")
@Composable
private fun DialogConnectionTestRow(
    apiUrl: String,
    apiKey: String,
    model: String,
    type: String,
    viewModel: ApiConfigViewModel?,
) {
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ApiTestResult?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                testing = true
                testResult = null
                viewModel?.testConnectionInDialog(apiUrl, apiKey, model, type) { result ->
                    testing = false
                    testResult = result
                }
            },
            enabled =
                viewModel != null && apiUrl.isNotBlank() && apiKey.isNotBlank() &&
                    model.isNotBlank() && !testing,
            shape = RoundedCornerShape(16.dp),
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("连接测试")
        }
        testResult?.let { result ->
            Text(
                text =
                    when (result) {
                        is ApiTestResult.Success -> "✅ ${result.message}"
                        is ApiTestResult.Error -> "❌ ${result.message}"
                    },
                style = MaterialTheme.typography.bodySmall,
                color =
                    when (result) {
                        is ApiTestResult.Success -> MaterialTheme.colorScheme.primary
                        is ApiTestResult.Error -> MaterialTheme.colorScheme.error
                    },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 接口类型下拉（OpenAI 兼容 / Anthropic / Gemini / 自定义）。
 * 对话、连接测试、模型列表均按所选类型走对应协议（见 LlmClient 的 ChatProtocol 分发）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun ApiTypeSelectorField(
    type: String,
    onTypeChange: (String) -> Unit,
) {
    var typeExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = typeExpanded,
        onExpandedChange = { typeExpanded = it },
    ) {
        OutlinedTextField(
            value = apiTypeLabel(type),
            onValueChange = {},
            readOnly = true,
            label = { Text("接口类型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            shape = RoundedCornerShape(16.dp),
        )
        ExposedDropdownMenu(
            expanded = typeExpanded,
            onDismissRequest = { typeExpanded = false },
        ) {
            API_TYPE_OPTIONS.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTypeChange(value)
                        typeExpanded = false
                    },
                )
            }
        }
    }
}

/**
 * API 配置编辑对话框。
 *
 * 功能：接口类型选择（OpenAI 兼容 / Anthropic / Gemini / 自定义）、获取模型列表、
 * 连接测试（按类型走对应协议）、SSE 流式传输、1M 上下文开关、
 * 禁用思考模式（仅对 OpenAI 兼容网关生效）、设为默认。
 *
 * 协议边界：当前版本对话请求固定为 OpenAI 兼容格式（LlmClient 单协议）；
 * 选择 Anthropic/Gemini 时连接测试与模型列表按其官方协议真实可用，
 * 对话请通过 OpenAI 兼容网关接入或选「自定义接口」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
fun ApiConfigEditorDialog(
    apiConfig: ApiConfig? = null,
    viewModel: ApiConfigViewModel? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        apiUrl: String,
        apiKey: String,
        model: String,
        setAsDefault: Boolean,
        type: String,
        context1M: Boolean,
        streamingEnabled: Boolean,
        thinkingDisabled: Boolean,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(apiConfig?.name ?: "") }
    var type by remember { mutableStateOf(apiConfig?.type ?: ApiType.OPENAI) }
    var apiUrl by remember { mutableStateOf(apiConfig?.apiUrl ?: "") }
    var apiKey by remember { mutableStateOf(apiConfig?.apiKey ?: "") }
    var model by remember { mutableStateOf(apiConfig?.model ?: "") }
    var context1M by remember { mutableStateOf(apiConfig?.context1M ?: false) }
    var streamingEnabled by remember { mutableStateOf(apiConfig?.streamingEnabled ?: true) }
    // 思考开关：新增配置时默认 ON（= 禁用思考）；编辑时沿用已存值
    var thinkingDisabled by remember { mutableStateOf(apiConfig?.thinkingDisabled ?: true) }
    var setAsDefault by remember { mutableStateOf(apiConfig?.isDefault ?: false) }
    val fetchedModels by viewModel?.fetchedModels?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
    val fetchingModels by viewModel?.fetchingModels?.collectAsState() ?: remember { mutableStateOf(false) }
    val fetchModelsError by viewModel?.fetchModelsError?.collectAsState() ?: remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (apiConfig == null) "添加 API 配置" else "编辑 API 配置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // 配置名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：OpenAI API") },
                    leadingIcon = {
                        Icon(Icons.Default.Label, null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                // 接口类型（选中自动填充预设地址）
                ApiTypeSelectorField(
                    type = type,
                    onTypeChange = { newType ->
                        type = newType
                        apiUrl = resolvePresetUrl(newType, apiUrl)
                    },
                )
                // 一键填入 Odys 中转站公开配置；API Key 由用户自行填写，不内置或上传密钥。
                OutlinedButton(
                    onClick = {
                        name = "Odys 中转站"
                        type = ApiType.CUSTOM
                        apiUrl = "https://api.odys.work/v1"
                        model = "Deepseek-V4.1-Flash"
                        streamingEnabled = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("填入 Odys 中转站配置")
                }
                Text(
                    text = "额度查询：https://api.odys.work/balance-check（需在浏览器打开）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // API 地址
                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    label = { Text("API 地址") },
                    placeholder = {
                        Text(defaultBaseUrlForType(type).ifBlank { "https://your-gateway.example.com/v1" })
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Link, null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                ApiUrlValidationMessage(apiUrl)
                // API 密钥
                ApiKeyField(
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                )
                // 模型 + 获取模型 + 拉取结果 + 连接测试
                ApiModelField(
                    model = model,
                    onModelChange = { model = it },
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    type = type,
                    viewModel = viewModel,
                    fetchedModels = fetchedModels,
                    fetchingModels = fetchingModels,
                    fetchModelsError = fetchModelsError,
                )
                DialogConnectionTestRow(
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    model = model,
                    type = type,
                    viewModel = viewModel,
                )
                // SSE 流式传输开关：按当前 API 配置保存，关闭后聊天使用整包响应
                SettingsSwitchRow(
                    title = "SSE 流式传输",
                    subtitle = "逐步显示模型回复；关闭后等待整包返回",
                    checked = streamingEnabled,
                    onCheckedChange = { streamingEnabled = it },
                )
                // 1M 上下文开关
                SettingsSwitchRow(
                    title = "1M 上下文",
                    subtitle = "该配置支持百万级上下文窗口时使用",
                    checked = context1M,
                    onCheckedChange = { context1M = it },
                )
                // 禁用思考模式开关（项目①）：按当前 API 配置持久化；
                // 请求层仅对 OpenAI 兼容网关注入 thinking 关闭参数（协议隔离）
                SettingsSwitchRow(
                    title = "禁用思考模式",
                    subtitle = "仅对 OpenAI 兼容网关生效，关闭模型的思考输出",
                    icon = Icons.Default.Psychology,
                    checked = thinkingDisabled,
                    onCheckedChange = { thinkingDisabled = it },
                )
                // 设为默认
                SettingsSwitchRow(
                    title = "设为默认配置",
                    subtitle = "作为新会话的默认 API",
                    icon = Icons.Default.Star,
                    checked = setAsDefault,
                    onCheckedChange = { setAsDefault = it },
                )
                // 按钮
                DialogConfirmButtons(
                    isAdd = apiConfig == null,
                    name = name,
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    model = model,
                    onDismiss = onDismiss,
                    onConfirm = {
                        onConfirm(
                            name,
                            apiUrl,
                            apiKey,
                            model,
                            setAsDefault,
                            type,
                            context1M,
                            streamingEnabled,
                            thinkingDisabled,
                        )
                    },
                )
            }
        }
    }
}

/**
 * 提示词编辑对话框（已在 PromptManagement.kt 中实现，这里保持一致性）
 */
@Composable
fun PromptEditorDialogWrapper(
    prompt: CustomPrompt? = null,
    onDismiss: () -> Unit,
    onConfirm: (CustomPrompt) -> Unit,
) {
    PromptEditorDialog(
        prompt = prompt,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
