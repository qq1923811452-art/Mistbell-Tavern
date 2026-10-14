package com.mistbell.tavern.android.ui.settings

import java.net.URI
import java.util.Locale

/**
 * URL rules for configurable API endpoints.
 *
 * Android's network security config only opts loopback/emulator hosts into cleartext HTTP.
 * Keep that platform policy strict and explain other HTTP endpoints in the settings UI.
 */
internal object ApiBaseUrlPolicy {
    private val cleartextAllowedHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun isValid(rawUrl: String): Boolean {
        val value = rawUrl.trim()
        if (value.isEmpty()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
    }

    fun requiresCleartextWarning(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host !in cleartextAllowedHosts
    }
}
