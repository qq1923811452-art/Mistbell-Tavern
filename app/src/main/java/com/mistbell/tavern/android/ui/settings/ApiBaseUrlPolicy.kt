package com.mistbell.tavern.android.ui.settings

import com.mistbell.tavern.android.util.ApiEndpointPolicy

/**
 * UI 侧对 [ApiEndpointPolicy] 的转发入口，保留原有调用点与测试不变。
 *
 * 判据本体已下沉到 `util`，以便数据层（备份恢复等）复用同一份规则；
 * 新增代码请直接使用 [ApiEndpointPolicy]。
 */
internal object ApiBaseUrlPolicy {
    fun isValid(rawUrl: String): Boolean = ApiEndpointPolicy.isValid(rawUrl)

    fun requiresCleartextWarning(rawUrl: String): Boolean = ApiEndpointPolicy.requiresCleartextWarning(rawUrl)

    fun writeRejectionReason(rawUrl: String): String? = ApiEndpointPolicy.writeRejectionReason(rawUrl)
}
