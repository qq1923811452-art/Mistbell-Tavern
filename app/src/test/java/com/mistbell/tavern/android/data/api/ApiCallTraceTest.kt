package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiCallTraceTest {
    @Test
    fun safeEndpointOmitsCredentialsAndQueryParameters() {
        assertEquals(
            "https://example.com/.../chat/completions",
            ApiCallTrace.safeEndpoint(
                "https://user:secret@example.com/v1/chat/completions?api_key=secret&token=hidden",
            ),
        )
    }

    @Test
    fun safeEndpointRedactsSecretLikePathSegmentsAndKeepsPort() {
        assertEquals(
            "http://127.0.0.1:8080/.../embeddings",
            ApiCallTrace.safeEndpoint(
                "http://127.0.0.1:8080/v1/sk-secret-token/embeddings?api_key=hidden",
            ),
        )
    }

    @Test
    fun safeEndpointReturnsFallbackForInvalidUrl() {
        assertEquals("<invalid-url>", ApiCallTrace.safeEndpoint("not a valid url"))
    }
}
