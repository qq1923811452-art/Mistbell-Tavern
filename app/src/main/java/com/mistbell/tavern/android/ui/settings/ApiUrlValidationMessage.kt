package com.mistbell.tavern.android.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Suppress("FunctionNaming")
@Composable
internal fun ApiUrlValidationMessage(apiUrl: String) {
    when {
        apiUrl.isNotBlank() && !ApiBaseUrlPolicy.isValid(apiUrl) -> {
            Text(
                text = "地址格式无效，请输入包含主机名的 http:// 或 https:// URL。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ApiBaseUrlPolicy.requiresCleartextWarning(apiUrl) -> {
            Text(
                text =
                    "无法保存：Android 网络安全策略阻止此地址使用明文 HTTP。请改用 HTTPS；仅 localhost、127.0.0.1 和 10.0.2.2 有例外。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
