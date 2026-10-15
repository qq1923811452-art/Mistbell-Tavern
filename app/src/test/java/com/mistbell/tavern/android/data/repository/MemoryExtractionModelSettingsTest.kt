package com.mistbell.tavern.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryExtractionModelSettingsTest {
    @Test
    fun `incomplete dedicated configuration stays disabled`() {
        assertNull(
            MemoryExtractionModelSettings(
                baseUrl = "",
                apiKey = "key",
                model = "qwen",
            ).toLlmConfigOrNull(),
        )
        assertNull(
            MemoryExtractionModelSettings(
                baseUrl = "https://relay.example/v1",
                apiKey = "key",
                model = "",
            ).toLlmConfigOrNull(),
        )
    }

    @Test
    fun `invalid base URL stays disabled`() {
        assertNull(
            MemoryExtractionModelSettings(
                baseUrl = "relay.example/v1",
                apiKey = "key",
                model = "qwen",
            ).toLlmConfigOrNull(),
        )
        assertNull(
            MemoryExtractionModelSettings(
                baseUrl = "https://",
                apiKey = "key",
                model = "qwen",
            ).toLlmConfigOrNull(),
        )
    }

    @Test
    fun `configured model is normalized and independent of chat credentials`() {
        val config = MemoryExtractionModelSettings(
            baseUrl = " https://relay.example/v1/ ",
            apiKey = " memory-key ",
            model = " qwen-memory ",
        ).toLlmConfigOrNull()

        requireNotNull(config)
        assertEquals("https://relay.example/v1", config.baseUrl)
        assertEquals("memory-key", config.apiKey)
        assertEquals("qwen-memory", config.model)
    }

    @Test
    fun remoteCleartextAndUserInfoEndpointsAreRejected() {
        val remoteHttp =
            MemoryExtractionModelSettings(
                baseUrl = "http://relay.example/v1",
                apiKey = "key",
                model = "qwen-memory",
            )
        assertNull(remoteHttp.toLlmConfigOrNull())
        assertEquals(
            "远程 API 必须使用 HTTPS；HTTP 仅允许本机回环地址",
            remoteHttp.endpointRejectionReason(),
        )

        val userInfoUrl =
            MemoryExtractionModelSettings(
                baseUrl = "https://user:secret@relay.example/v1",
                apiKey = "key",
                model = "qwen-memory",
            )
        assertNull(userInfoUrl.toLlmConfigOrNull())
        assertEquals("API 基础地址不能包含用户名或密码", userInfoUrl.endpointRejectionReason())
    }

    @Test
    fun `remote endpoint requires API key`() {
        assertNull(
            MemoryExtractionModelSettings(
                baseUrl = "https://relay.example/v1",
                apiKey = "",
                model = "qwen-memory",
            ).toLlmConfigOrNull(),
        )
    }

    @Test
    fun `unreadable stored key disables dedicated config until reentered`() {
        assertNull(
            MemoryExtractionModelSettings(
                baseUrl = "https://relay.example/v1",
                apiKey = "",
                model = "qwen-memory",
                keyNeedsReentry = true,
            ).toLlmConfigOrNull(),
        )
    }

    @Test
    fun `blank API key is allowed for local compatible endpoints`() {
        val config = MemoryExtractionModelSettings(
            baseUrl = "http://127.0.0.1:9379/v1",
            apiKey = "",
            model = "local-memory-model",
        ).toLlmConfigOrNull()

        requireNotNull(config)
        assertEquals("", config.apiKey)
    }
}
