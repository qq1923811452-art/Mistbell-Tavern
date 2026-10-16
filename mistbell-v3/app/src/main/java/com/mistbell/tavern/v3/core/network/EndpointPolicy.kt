package com.mistbell.tavern.v3.core.network

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

data class EndpointValidation(
    val isAllowed: Boolean,
    val normalizedBaseUrl: String? = null,
    val reason: String? = null,
)

object EndpointPolicy {
    fun validateBaseUrl(rawValue: String): EndpointValidation {
        val value = rawValue.trim()
        if (value.isEmpty()) {
            return EndpointValidation(isAllowed = false, reason = "Base URL is required.")
        }

        val uri = try {
            URI(value).normalize()
        } catch (_: URISyntaxException) {
            return EndpointValidation(isAllowed = false, reason = "Base URL is malformed.")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT)
        if (host.isNullOrBlank() || uri.rawUserInfo != null) {
            return EndpointValidation(
                isAllowed = false,
                reason = "Base URL must contain a host and must not contain credentials.",
            )
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return EndpointValidation(
                isAllowed = false,
                reason = "Base URL must not contain a query or fragment.",
            )
        }

        val isHttps = scheme == "https"
        val isLoopbackHttp = scheme == "http" && host in LOOPBACK_HOSTS
        if (!isHttps && !isLoopbackHttp) {
            return EndpointValidation(
                isAllowed = false,
                reason = "HTTPS is required except for explicit loopback development endpoints.",
            )
        }

        return EndpointValidation(
            isAllowed = true,
            normalizedBaseUrl = value.trimEnd('/'),
        )
    }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
}
