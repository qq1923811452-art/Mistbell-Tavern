package com.mistbell.tavern.v3.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.mistbell.tavern.v3.core.network.ChatConnectionTester
import kotlinx.coroutines.launch
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

private const val DIALOG_MAX_HEIGHT_DP = 520
private const val CONTENT_SPACING_DP = 12
private const val SWITCH_SPACING_DP = 8
private const val ROW_SPACING_DP = 12
private const val SMALL_SPACING_DP = 4
private const val PROFILE_NAME_MAX_LENGTH = 40
private const val BASE_URL_MAX_LENGTH = 2048
private const val API_KEY_MAX_LENGTH = 4096
private const val MODEL_ID_MAX_LENGTH = 200
private const val OPENAI_COMPATIBLE_LABEL = "OpenAI 兼容"
private const val FORM_FOOTNOTE =
    "记忆整理开关与 API 密钥只保留在本次对话框；ApiProfile 只保存连接参数与凭据句柄。"

/**
 * 新增或编辑一条 API 配置的纯 UI 对话框。
 *
 * 不访问网络，也不读写本地存储；确认时回传的 [ApiProfile] 只包含连接参数与凭据句柄。
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun ApiConfigDialog(
    initial: ApiProfile,
    onDismiss: () -> Unit,
    onConfirm: (ApiProfile, String) -> Unit,
) {
    val state = remember(initial) { ApiConfigFormState(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "新增 API 配置" else "编辑 API 配置") },
        text = { ApiConfigDialogContent(state) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(state.toProfile(initial), state.apiKey) },
                enabled = state.canConfirm,
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun ApiConfigDialogContent(state: ApiConfigFormState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = DIALOG_MAX_HEIGHT_DP.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CONTENT_SPACING_DP.dp),
    ) {
        ApiConfigTextField(
            value = state.name,
            onValueChange = { state.name = it.take(PROFILE_NAME_MAX_LENGTH) },
            label = "配置名称",
            placeholder = "例如：主力 Odys",
            supportingText = "${state.name.length}/$PROFILE_NAME_MAX_LENGTH",
        )
        ApiConfigTypeField()
        ApiConfigTextField(
            value = state.baseUrl,
            onValueChange = { state.baseUrl = it.take(BASE_URL_MAX_LENGTH) },
            label = "API 地址",
            placeholder = "https://api.example.com/v1",
            supportingText = "需包含接口版本前缀（如 /v1）。",
        )
        ApiConfigSecretField(
            value = state.apiKey,
            onValueChange = { state.apiKey = it.take(API_KEY_MAX_LENGTH) },
            hasSavedHandle = state.hasSavedHandle,
        )
        ApiConfigTextField(
            value = state.modelId,
            onValueChange = { state.modelId = it.take(MODEL_ID_MAX_LENGTH) },
            label = "模型",
            placeholder = "例如：gpt-4o-mini",
        )
        ApiConfigTestAction(state)
        ApiConfigSwitchSection(state)
        Text(
            text = FORM_FOOTNOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ApiConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText =
            if (supportingText == null) {
                null
            } else {
                { Text(supportingText) }
            },
        singleLine = true,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun ApiConfigTypeField() {
    OutlinedTextField(
        value = OPENAI_COMPATIBLE_LABEL,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        label = { Text("接口类型") },
        supportingText = { Text("当前仅支持 OpenAI 兼容协议") },
        readOnly = true,
        singleLine = true,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun ApiConfigSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    hasSavedHandle: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API 密钥") },
        placeholder = { Text("输入 API 密钥") },
        supportingText = {
            Text(
                if (hasSavedHandle) {
                    "已保存凭据；留空沿用原凭据。"
                } else {
                    "密钥仅用于本次测试，不会写入 ApiProfile。"
                },
            )
        },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun ApiConfigTestAction(state: ApiConfigFormState) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(SMALL_SPACING_DP.dp)) {
        OutlinedButton(
            onClick = {
                state.testStatus = "测试中…"
                scope.launch {
                    val result = ChatConnectionTester().test(
                        baseUrl = state.baseUrl,
                        modelId = state.modelId,
                        apiKey = state.apiKey,
                    )
                    state.testStatus = result.message
                }
            },
        ) {
            Text("测试连接")
        }
        if (state.testStatus.isNotBlank()) {
            Text(
                text = state.testStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ApiConfigSwitchSection(state: ApiConfigFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(SWITCH_SPACING_DP.dp)) {
        ApiConfigSwitchRow(
            title = "SSE 流式",
            description = "逐段接收模型回复；关闭后等待完整响应。",
            checked = state.stream,
            onCheckedChange = { state.stream = it },
        )
        ApiConfigSwitchRow(
            title = "1M 上下文",
            description = "请求超长上下文，仅部分模型支持。",
            checked = state.longContext,
            onCheckedChange = { state.longContext = it },
        )
        ApiConfigSwitchRow(
            title = "禁用思考",
            description = "不请求模型的思考过程。",
            checked = !state.thinkingEnabled,
            onCheckedChange = { state.thinkingEnabled = !it },
        )
        ApiConfigSwitchRow(
            title = "记忆整理",
            description = "对话结束后自动整理长期记忆（待接入）。",
            checked = state.memoryOrganizeEnabled,
            onCheckedChange = { state.memoryOrganizeEnabled = it },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ApiConfigSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 对话框内的临时表单状态。
 *
 * API 密钥与“记忆整理”开关只存在于本次对话框内存中：密钥不写入 [ApiProfile]，
 * 记忆整理尚未在 [ApiProfile] 中建模，确认时不会回写。
 */
private class ApiConfigFormState(initial: ApiProfile) {
    var name by mutableStateOf(initial.name.take(PROFILE_NAME_MAX_LENGTH))
    var baseUrl by mutableStateOf(initial.baseUrl.take(BASE_URL_MAX_LENGTH))
    var modelId by mutableStateOf(initial.modelId.take(MODEL_ID_MAX_LENGTH))
    var apiKey by mutableStateOf("")
    var stream by mutableStateOf(initial.stream)
    var thinkingEnabled by mutableStateOf(initial.thinkingEnabled)
    var longContext by mutableStateOf(initial.longContext)
    var memoryOrganizeEnabled by mutableStateOf(false)
    var testStatus by mutableStateOf("")
    val hasSavedHandle: Boolean = !initial.handle.isNullOrBlank()

    val canConfirm: Boolean
        get() = name.isNotBlank() && baseUrl.isNotBlank()

    fun toProfile(initial: ApiProfile): ApiProfile =
        initial.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            modelId = modelId.trim(),
            stream = stream,
            thinkingEnabled = thinkingEnabled,
            longContext = longContext,
        )
}
