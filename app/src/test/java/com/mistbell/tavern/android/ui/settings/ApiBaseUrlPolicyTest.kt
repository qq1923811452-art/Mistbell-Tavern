package com.mistbell.tavern.android.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiBaseUrlPolicyTest {
    @Test
    fun `accepts only absolute HTTP or HTTPS endpoint URLs with a host`() {
        listOf(
            "https://api.example.com/v1",
            "http://localhost:8080/v1",
            "http://192.168.1.20:8000/v1",
        ).forEach { assertTrue("$it should be a valid endpoint", ApiBaseUrlPolicy.isValid(it)) }

        listOf(
            "",
            "api.example.com/v1",
            "ftp://api.example.com/v1",
            "https:///v1",
            "https://user:password@api.example.com/v1",
            "https://api.example.com/v1#fragment",
        ).forEach { assertFalse("$it should be rejected", ApiBaseUrlPolicy.isValid(it)) }
    }

    @Test
    fun `warns for HTTP hosts not explicitly allowed by network security config`() {
        listOf(
            "http://192.168.1.20:8000/v1",
            "http://gateway.office.local/v1",
        ).forEach { assertTrue("$it should show a cleartext warning", ApiBaseUrlPolicy.requiresCleartextWarning(it)) }

        listOf(
            "https://api.example.com/v1",
            "http://localhost:8080/v1",
            "http://127.0.0.1:8080/v1",
            "http://10.0.2.2:8080/v1",
        ).forEach { url ->
            assertFalse(
                "$url should not show a cleartext warning",
                ApiBaseUrlPolicy.requiresCleartextWarning(url),
            )
        }
    }
}
