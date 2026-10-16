package com.mistbell.tavern.v3.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPolicyTest {
    @Test
    fun acceptsHttpsAndNormalizesTrailingSlash() {
        val result = EndpointPolicy.validateBaseUrl("  https://api.example.com/v1/  ")

        assertTrue(result.isAllowed)
        assertEquals("https://api.example.com/v1", result.normalizedBaseUrl)
        assertNull(result.reason)
    }

    @Test
    fun acceptsExplicitLoopbackHttpForLocalInference() {
        listOf(
            "http://localhost:8080/v1",
            "http://127.0.0.1:8080/v1",
            "http://[::1]:8080/v1",
        ).forEach { url ->
            assertTrue("$url should be allowed", EndpointPolicy.validateBaseUrl(url).isAllowed)
        }
    }

    @Test
    fun rejectsRemoteHttp() {
        val result = EndpointPolicy.validateBaseUrl("http://api.example.com/v1")

        assertFalse(result.isAllowed)
        assertNotNull(result.reason)
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        val result = EndpointPolicy.validateBaseUrl("https://user:secret@api.example.com/v1")

        assertFalse(result.isAllowed)
    }

    @Test
    fun rejectsQueryAndFragment() {
        assertFalse(EndpointPolicy.validateBaseUrl("https://api.example.com/v1?token=x").isAllowed)
        assertFalse(EndpointPolicy.validateBaseUrl("https://api.example.com/v1#fragment").isAllowed)
    }

    @Test
    fun rejectsBlankMalformedAndHostlessValues() {
        listOf("", "   ", "not a url", "https:///v1", "ftp://api.example.com/v1")
            .forEach { url ->
                assertFalse("Should reject '$url'", EndpointPolicy.validateBaseUrl(url).isAllowed)
            }
    }
}
