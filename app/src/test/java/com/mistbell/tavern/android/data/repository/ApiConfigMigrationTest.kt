package com.mistbell.tavern.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * providers_json → api_configs 迁移的纯函数单测（解析 / id 保留 / 默认归属 / 覆盖键映射）。
 */
class ApiConfigMigrationTest {
    // ---- parseProvidersJson ----

    @Test
    fun `正常 JSON 解析出提供商列表`() {
        val json =
            """[
              {"id":"p1","name":"OpenAI","endpoint":"https://api.openai.com/v1","apiKey":"sk-x","selectedModel":"gpt-4o"},
              {"id":"p2","name":"Proxy","endpoint":"https://proxy.example.com","apiKey":"","selectedModel":"claude"}
            ]"""
        val providers = ApiConfigMigration.parseProvidersJson(json)
        assertEquals(2, providers.size)
        assertEquals("p1", providers[0].id)
        assertEquals("https://api.openai.com/v1", providers[0].endpoint)
        assertEquals("gpt-4o", providers[0].selectedModel)
    }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun `非空坏 JSON 抛出异常以阻止迁移被误标完成`() {
        ApiConfigMigration.parseProvidersJson("{not json")
    }

    @Test
    fun `空 JSON 文本表示没有遗留提供商`() {
        assertTrue(ApiConfigMigration.parseProvidersJson("").isEmpty())
        assertTrue(ApiConfigMigration.parseProvidersJson("  ").isEmpty())
        assertTrue(ApiConfigMigration.parseProvidersJson("[]").isEmpty())
    }

    // ---- toApiConfigEntities ----

    @Test
    fun `迁移保留原 ProviderConfig id 且 activeId 为默认`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[
                  {"id":"p1","name":"A","endpoint":"https://u1.example.com","apiKey":"k1","selectedModel":"m1"},
                  {"id":"p2","name":"B","endpoint":"https://u2.example.com","apiKey":"k2","selectedModel":"m2"}
                ]""",
            )
        val entities = ApiConfigMigration.toApiConfigEntities(providers, activeId = "p2", now = 1000L)

        assertEquals(2, entities.size)
        // id 保留：存量 session.providerId 引用不断链
        assertEquals("p1", entities[0].id)
        assertEquals("p2", entities[1].id)
        // activeId 为默认，其余非默认
        assertEquals(false, entities[0].isDefault)
        assertEquals(true, entities[1].isDefault)
        // 字段映射
        assertEquals("https://u2.example.com", entities[1].apiUrl)
        assertEquals("k2", entities[1].apiKey)
        assertEquals("m2", entities[1].model)
        // sortOrder 按列表顺序
        assertEquals(0, entities[0].sortOrder)
        assertEquals(1, entities[1].sortOrder)
        // 缺省 type 回落 openai，context1m 缺省 false
        assertEquals("openai", entities[0].type)
        assertEquals(false, entities[0].context1m)
    }

    @Test
    fun unsafeLegacyEndpointIsQuarantinedWithoutBlockingOtherProviders() {
        assertEquals("", ApiConfigMigration.endpointForMigration("http://api.example.com/v1"))
        assertEquals("", ApiConfigMigration.endpointForMigration("https://user:secret@example.com/v1"))
        assertEquals("https://api.example.com/v1", ApiConfigMigration.endpointForMigration("https://api.example.com/v1"))
        assertEquals(
            "http://10.0.2.2:3000/",
            ApiConfigMigration.endpointForMigration("http://10.0.2.2:3000/"),
        )
    }

    @Test
    fun `迁移保留旧提供商的 type 与 1M 上下文标记`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[
              {"id":"p1","name":"A","endpoint":"https://u.example.com","apiKey":"k","selectedModel":"m","type":"custom","context1M":true},
              {"id":"p2","name":"B","endpoint":"https://u.example.com","apiKey":"k","selectedModel":"m","type":""}
            ]""",
            )
        val entities = ApiConfigMigration.toApiConfigEntities(providers, activeId = "", now = 0L)

        assertEquals("custom", entities[0].type)
        assertEquals(true, entities[0].context1m)
        // 空 type 回落 openai
        assertEquals("openai", entities[1].type)
    }

    @Test
    fun `无 activeId 时第一个提供商为默认`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[{"id":"p1","name":"A","endpoint":"https://u.example.com","apiKey":"k","selectedModel":"m"}]""",
            )
        val entities = ApiConfigMigration.toApiConfigEntities(providers, activeId = "", now = 0L)
        assertEquals(true, entities.single().isDefault)
    }

    // ---- activeProviderOverrides ----

    @Test
    fun `高级参数映射到 llm 覆盖键且 null 为空串`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[{"id":"p1","name":"A","endpoint":"https://u.example.com","apiKey":"k","selectedModel":"m",
                     "temperature":0.7,"top_p":0.9,"top_k":40,"frequency_penalty":0.1,"max_tokens":2048}]""",
            )
        val overrides = ApiConfigMigration.activeProviderOverrides(providers.single()).toMap()

        assertEquals("0.7", overrides["llm_temperature"])
        assertEquals("0.9", overrides["llm_top_p"])
        assertEquals("40", overrides["llm_top_k"])
        assertEquals("0.1", overrides["llm_frequency_penalty"])
        assertEquals("2048", overrides["llm_max_tokens"])
    }

    @Test
    fun `高级参数全缺省映射为空串`() {
        val providers =
            ApiConfigMigration.parseProvidersJson(
                """[{"id":"p1","name":"A","endpoint":"https://u.example.com","apiKey":"k","selectedModel":"m"}]""",
            )
        val overrides = ApiConfigMigration.activeProviderOverrides(providers.single())
        assertEquals(5, overrides.size)
        assertTrue(overrides.all { it.second.isEmpty() })
    }
}
