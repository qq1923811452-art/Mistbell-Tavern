package com.mistbell.tavern.v3.feature.settings

/**
 * 设置页多 API 配置的纯数据模型。
 *
 * [handle] 只保存凭据的不透明句柄，不保存明文 API 密钥。本文件不读取本地设置，
 * 也不访问数据库或网络；持久化由上层负责。
 */
data class ApiProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val handle: String? = null,
    val isDefault: Boolean = false,
    val stream: Boolean = true,
    val thinkingEnabled: Boolean = false,
    val longContext: Boolean = false,
)

/**
 * 内置 API 配置的默认值。
 */
object ApiProfileDefaults {
    const val ODYS_ID = "odys"
    const val ODYS_NAME = "Odys"
    const val ODYS_BASE_URL = "https://api.odys.work/v1"

    /**
     * 返回 Odys 官方 OpenAI 兼容端点的初始配置。
     *
     * 模型留空，由用户在设置页按实际订阅填写；密钥句柄在保存后由上层写入。
     */
    fun odys(): ApiProfile =
        ApiProfile(
            id = ODYS_ID,
            name = ODYS_NAME,
            baseUrl = ODYS_BASE_URL,
            modelId = "",
            handle = null,
            isDefault = true,
            stream = true,
            thinkingEnabled = false,
            longContext = false,
        )
}
