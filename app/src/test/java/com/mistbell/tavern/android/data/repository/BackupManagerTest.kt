package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份系统的纯函数防线（JVM，无 Android 依赖）：
 * 清单校验（防跨版本/坏包误恢复）、容错列表解码（坏元素不拖垮整表）、
 * settings 敏感条目的明文往返语义。
 */
class BackupManagerTest {
    private fun manifestJson(format: Int): String =
        """{"format":$format,"appVersion":"0.8.0-beta","createdAt":"2026-09-26 12:00:00","counts":{}}"""

    // ---- 清单校验 ----

    @Test
    fun `合法清单按版本解析返回`() {
        val m = BackupManager.parseManifest(manifestJson(BackupManager.FORMAT_VERSION))
        assertEquals(BackupManager.FORMAT_VERSION, m.format)
        assertEquals("0.8.0-beta", m.appVersion)
    }

    @Test
    fun `高于支持版本的备份被拒绝并给出可懂提示`() {
        try {
            BackupManager.parseManifest(manifestJson(BackupManager.FORMAT_VERSION + 1))
            fail("高版本备份应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("提示应包含版本信息", e.message!!.contains("v" + (BackupManager.FORMAT_VERSION + 1)))
        }
    }

    @Test
    fun `非法格式与垃圾文本被拒绝`() {
        try {
            BackupManager.parseManifest(manifestJson(0))
            fail("format=0 应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("非法"))
        }
        try {
            BackupManager.parseManifest("not a json at all")
            fail("垃圾文本应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("清单"))
        }
    }

    // ---- 容错列表解码 ----

    @Test
    fun `列表整体解码与逐元素容错`() {
        val good = """[{"key":"a","value":"1"},{"key":"b","value":"2"}]"""
        val list = BackupManager.decodeList(good, SettingsEntity.serializer())
        assertEquals(2, list.size)

        // 第二个元素损坏：应跳过坏元素保住好元素，而不是整表丢弃
        val mixed = """[{"key":"a","value":"1"},{"key":42},{"value":"3"}]"""
        val recovered = BackupManager.decodeList(mixed, SettingsEntity.serializer())
        assertEquals(1, recovered.size)
        assertEquals("a", recovered[0].key)
    }

    @Test
    fun `空值与非数组输入安全返回空表`() {
        assertTrue(BackupManager.decodeList(null, SettingsEntity.serializer()).isEmpty())
        assertTrue(BackupManager.decodeList("{}", SettingsEntity.serializer()).isEmpty())
        assertTrue(BackupManager.decodeList("", SettingsEntity.serializer()).isEmpty())
    }

    // ---- settings 敏感条目 ----

    @Test
    fun `明文设置原样进包且不标记敏感`() {
        val entry = BackupManager.toBackupEntry(SettingsEntity("dark_mode", "system"))
        assertEquals("dark_mode", entry.key)
        assertEquals("system", entry.value)
        assertFalse(entry.sensitive)
        // 明文往返：entity → entry → entity 值不变
        val restored = BackupManager.toSettingsEntity(entry)
        assertEquals("system", restored.value)
    }

    @Test
    fun `legacy credential keys are marked sensitive even when stored as plaintext`() {
        val entry = BackupManager.toBackupEntry(SettingsEntity("llm_api_key", "legacy-secret"))

        assertEquals("legacy-secret", entry.value)
        assertTrue(entry.sensitive)
    }

    @Test
    fun `backup rejects an empty result from decrypting an encrypted secret`() {
        try {
            BackupManager.requireDecryptedSecretValue("llm_api_key", "")
            fail("Undecryptable encrypted credentials must stop backup export")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("中止备份"))
        }
    }

    @Test
    fun `safe ZIP directory entries with trailing slashes are accepted`() {
        BackupManager.validateBackupEntryPath("themes/", isDirectory = true)
        BackupManager.validateBackupEntryPath("themes/default/", isDirectory = true)
    }

    @Test
    fun `unsafe ZIP directory entry paths are rejected`() {
        listOf("../", "themes/../", "/themes/", "themes//", "themes/./").forEach { path ->
            try {
                BackupManager.validateBackupEntryPath(path, isDirectory = true)
                fail("Unsafe ZIP directory path should be rejected: $path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("路径"))
            }
        }
    }

    @Test
    fun `unsafe theme pack IDs are rejected`() {
        listOf("../outside", "folder/name", "folder\\\\name", "..").forEach { id ->
            try {
                BackupManager.validateThemePackId(id)
                fail("Unsafe theme pack ID should be rejected: $id")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("路径"))
            }
        }
        BackupManager.validateThemePackId("safe-theme_123")
    }

    @Test
    fun `API 配置备份 DTO 保留全部字段并允许空密钥恢复`() {
        val entry = BackupManager.ApiConfigBackupEntry(
            id = "provider-1",
            name = "Local provider",
            apiUrl = "https://example.invalid/v1",
            apiKey = "",
            model = "model-x",
            isDefault = true,
            lastTestStatus = "SUCCESS",
            lastTestTime = 123L,
            sortOrder = 4,
            type = "custom",
            context1m = true,
            streamingEnabled = false,
            thinkingDisabled = true,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val restored = entry.toEntity()

        assertEquals(entry.id, restored.id)
        assertEquals(entry.apiUrl, restored.apiUrl)
        assertEquals("", restored.apiKey)
        assertEquals(entry.model, restored.model)
        assertEquals(entry.isDefault, restored.isDefault)
        assertEquals(entry.lastTestStatus, restored.lastTestStatus)
        assertEquals(entry.lastTestTime, restored.lastTestTime)
        assertEquals(entry.sortOrder, restored.sortOrder)
        assertEquals(entry.type, restored.type)
        assertEquals(entry.context1m, restored.context1m)
        assertEquals(entry.streamingEnabled, restored.streamingEnabled)
        assertEquals(entry.thinkingDisabled, restored.thinkingDisabled)
        assertEquals(entry.createdAt, restored.createdAt)
        assertEquals(entry.updatedAt, restored.updatedAt)
    }

    @Test
    fun `自定义提示词备份 DTO 完整往返`() {
        val entity = CustomPromptEntity(
            id = "prompt-1",
            name = "Writing style",
            content = "Keep the voice concise.",
            type = "WRITING_STYLE",
            position = "SYSTEM_START",
            isEnabled = false,
            priority = 7,
            tags = """["style","concise"]""",
            description = "test prompt",
            createdAt = 100L,
            updatedAt = 200L,
        )

        val entry = BackupManager.CustomPromptBackupEntry.fromEntity(entity)
        val encoded = BackupManager.json.encodeToString(
            ListSerializer(BackupManager.CustomPromptBackupEntry.serializer()),
            listOf(entry),
        )
        val decoded = BackupManager.decodeList(
            encoded,
            BackupManager.CustomPromptBackupEntry.serializer(),
        )

        assertEquals(listOf(entity), decoded.map(BackupManager.CustomPromptBackupEntry::toEntity))
    }

    @Test
    fun `空 settings 表序列化往返`() {
        val encoded =
            Json.encodeToString(
                ListSerializer(BackupManager.SettingsBackupEntry.serializer()),
                emptyList(),
            )
        val decoded = BackupManager.decodeList(encoded, BackupManager.SettingsBackupEntry.serializer())
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `credential aliases are classified as sensitive backup keys`() {
        listOf(
            "openai_token",
            "custom-token",
            "embedding_secret",
            "auth_header",
            "authorization",
            "client_secret",
            "service_password",
            "private_key",
            "api-key",
            "customSecretValue",
            "servicePassword",
            "authorizationHeader",
            "myCredential",
        ).forEach { key ->
            assertTrue("$key should be considered sensitive", BackupManager.isSensitiveSettingKey(key))
        }
    }

    @Test
    fun `numeric token settings are not classified as credentials`() {
        listOf(
            "context_token_limit",
            "max_tokens",
            "llm_max_tokens",
            "prompt_tokens",
            "completion_tokens",
        ).forEach { key ->
            assertFalse("$key is a numeric setting, not a credential", BackupManager.isSensitiveSettingKey(key))
        }
    }
}
