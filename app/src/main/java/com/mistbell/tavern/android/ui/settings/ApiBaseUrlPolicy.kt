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

    fun isValid(rawUrl: String): Boolean = validateUri(rawUrl.trim())

    fun requiresCleartextWarning(rawUrl: String): Boolean =
        parseUri(rawUrl)?.let { uri ->
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host?.lowercase(Locale.ROOT)?.let { host -> host !in cleartextAllowedHosts } == true
        } ?: false

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
