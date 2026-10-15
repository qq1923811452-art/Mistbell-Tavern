package com.mistbell.tavern.android.util

import java.net.URI
import java.util.Locale

/**
 * 可配置 API 端点的地址规则。
 *
 * **这是写入层的唯一判据。** Android 的 network security config 只把 loopback/模拟器主机
 * 放行明文 HTTP，本对象保持与该平台策略一致；UI 上的按钮禁用只是体验优化，**不能替代**在
 * 真正落库/入库前调用 [writeRejectionReason]。
 *
 * 放在 `util` 而非 `ui`：数据层（备份恢复等）也必须复用同一判据，避免出现
 * 「一份规则两处实现」而逐渐漂移。
 */
object ApiEndpointPolicy {
    private val cleartextAllowedHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun isValid(rawUrl: String): Boolean = validateUri(rawUrl.trim())

    fun requiresCleartextWarning(rawUrl: String): Boolean =
        parseUri(rawUrl.trim())?.let { uri ->
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host?.lowercase(Locale.ROOT)?.let { host -> host !in cleartextAllowedHosts } == true
        } ?: false

    /**
     * 写入前的统一判定：返回 `null` 表示允许写入，否则返回可直接展示给用户的拒绝原因。
     * 任何持久化 endpoint 的入口都应先过这一关。
     */
    fun writeRejectionReason(rawUrl: String): String? =
        when {
            !isValid(rawUrl) ->
                "保存失败：API 地址格式无效，请输入包含主机名的 http:// 或 https:// URL。"
            requiresCleartextWarning(rawUrl) ->
                "保存失败：Android 网络安全策略阻止此地址使用明文 HTTP，请改用 HTTPS。"
            else -> null
        }

    private fun validateUri(value: String): Boolean =
        value.isNotEmpty() &&
            parseUri(value)?.let { uri ->
                uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawFragment == null
            } == true

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
}
