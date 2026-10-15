package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.util.ApiEndpointPolicy

/** Quarantine unsafe API endpoints imported from an untrusted backup. */
internal fun endpointForRestore(endpoint: String): String = if (ApiEndpointPolicy.writeRejectionReason(endpoint) == null) endpoint else ""

/** Add the config ID to Keystore failures so a failed restore is diagnosable. */
internal fun wrapRestoredApiKey(
    id: String,
    plainApiKey: String,
    wrap: (String) -> String,
): String =
    try {
        wrap(plainApiKey)
    } catch (e: IllegalStateException) {
        throw IllegalStateException("无法加密备份中 API 配置 $id 的密钥，已中止恢复", e)
    }
