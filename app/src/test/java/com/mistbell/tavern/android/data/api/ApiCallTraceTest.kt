package com.mistbell.tavern.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiCallTraceTest {
    @Test
    fun safeEndpointOmitsCredentialsAndQueryParameters() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            ApiCallTrace.safeEndpoint(
                "https://user:secret@example.com/v1/chat/completions?api_key=secret&token=hidden",
            ),
        )
    }

    @Test
    fun safeEndpointReturnsFallbackForInvalidUrl() {
        assertEquals("<invalid-url>", ApiCallTrace.safeEndpoint("not a valid url"))
    }
}
