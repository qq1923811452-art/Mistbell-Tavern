package com.mistbell.tavern.android.data.repository

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.local.entity.CharacterEntity
import com.mistbell.tavern.android.data.local.entity.CustomPromptEntity
import com.mistbell.tavern.android.data.local.entity.MemoryEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.local.entity.VectorMemoryEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.util.ApiEndpointPolicy
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全量备份/恢复（SETTINGS.md S4）：单 zip 打包全部 Room 表 + 主题包资产文件 + 向量库文件。
 *
 * 设计要点：
 * - **敏感字段明文进包**：settings 表中 SecureStore 密文（llm_api_key / providers_json 等）
 *   导出时解密为明文并标记 sensitive，恢复时重新 wrap——密文绑设备 Keystore，跨机恢复必失败，
 *   明文进用户自选位置的 zip 是用户显式持有的导出物（与导出角色卡同级）。
 * - **恢复为合并且跳过已存在主键**：不删本地数据，重复恢复/跨机合并不产生重复与丢失。
 * - 恢复全程单事务落库；主题资产/向量文件在事务提交后写入（文件不可回滚，不参与原子性）。
 */
class BackupManager(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database

    /** 备份结果摘要（导出与恢复共用，UI 直接拼提示文案） */
    @Serializable
    data class BackupSummary(
        val added: Map<String, Int> = emptyMap(),
        val skipped: Map<String, Int> = emptyMap(),
        val warnings: List<String> = emptyList(),
    ) {
        val totalAdded: Int get() = added.values.sum()
        val totalSkipped: Int get() = skipped.values.sum()
    }

    @Serializable
    data class BackupManifest(
        val format: Int,
        val appVersion: String,
        val createdAt: String,
        val counts: Map<String, Int> = emptyMap(),
    )

    /** settings 表条目：sensitive=true 的值在包内为明文，恢复时重新加密落库 */
    @Serializable
    data class SettingsBackupEntry(
        val key: String,
        val value: String,
        val sensitive: Boolean = false,
    )

    /** API 配置备份 DTO：API Key 在 ZIP 中明文保存，恢复时重新绑定本机 Keystore。 */
    @Serializable
    data class ApiConfigBackupEntry(
        val id: String,
        val name: String,
        val apiUrl: String,
        val apiKey: String,
        val model: String,
        val isDefault: Boolean = false,
        val lastTestStatus: String = "UNKNOWN",
        val lastTestTime: Long = 0L,
        val sortOrder: Int = 0,
        val type: String = "openai",
        val context1m: Boolean = false,
        val streamingEnabled: Boolean = true,
        val thinkingDisabled: Boolean = false,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun toEntity(): ApiConfigEntity {
            // Backup endpoints are untrusted. Quarantine unsafe values as blank rather than aborting
            // the whole restore; decodeTables adds a warning so the user can reconfigure this entry.
            val safeApiUrl = BackupManager.endpointForRestore(apiUrl)
            val plainApiKey =
                when {
                    apiKey.isEmpty() -> ""
                    apiKey.startsWith(SecureStore.PREFIX) -> {
                        check(SecureStore.isEncrypted(apiKey)) {
                            "备份中的 API 配置 $id 使用不可解密的密钥，已中止恢复；请重新输入 API Key"
                        }
                        SecureStore.unwrap(apiKey).also { decrypted ->
                            check(decrypted.isNotEmpty()) {
                                "备份中的 API 配置 $id 无法解密，已中止恢复；请重新输入 API Key"
                            }
                        }
                    }
                    else -> apiKey
                }
            return ApiConfigEntity(
                id = id,
                name = name,
                apiUrl = safeApiUrl,
                apiKey = wrapRestoredApiKey(id, plainApiKey, SecureStore::wrap),
                model = model,
                isDefault = isDefault,
                lastTestStatus = lastTestStatus,
                lastTestTime = lastTestTime,
                sortOrder = sortOrder,
                type = type,
                context1m = context1m,
                streamingEnabled = streamingEnabled,
                thinkingDisabled = thinkingDisabled,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }

        companion object {
            fun fromEntity(entity: ApiConfigEntity): ApiConfigBackupEntry {
                val encrypted = SecureStore.isEncrypted(entity.apiKey)
                check(!entity.apiKey.startsWith(SecureStore.PREFIX) || encrypted) {
                    "API 配置 ${entity.id} 的加密格式损坏，已中止备份以避免丢失敏感数据"
                }
                val plainKey = if (encrypted) SecureStore.unwrap(entity.apiKey) else entity.apiKey
                check(!encrypted || plainKey.isNotEmpty()) {
                    "无法解密 API 配置 ${entity.id}，已中止备份以避免丢失敏感数据"
                }
                return ApiConfigBackupEntry(
                    id = entity.id, name = entity.name, apiUrl = entity.apiUrl,
                    apiKey = plainKey, model = entity.model, isDefault = entity.isDefault,
                    lastTestStatus = entity.lastTestStatus, lastTestTime = entity.lastTestTime,
                    sortOrder = entity.sortOrder, type = entity.type, context1m = entity.context1m,
                    streamingEnabled = entity.streamingEnabled, thinkingDisabled = entity.thinkingDisabled,
                    createdAt = entity.createdAt, updatedAt = entity.updatedAt,
                )
            }
        }
    }

    @Serializable
    data class CustomPromptBackupEntry(
        val id: String,
        val name: String,
        val content: String,
        val type: String,
        val position: String,
        val isEnabled: Boolean = true,
        val priority: Int = 0,
        val tags: String = "",
        val description: String = "",
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun toEntity() =
            CustomPromptEntity(
                id = id,
                name = name,
                content = content,
                type = type,
                position = position,
                isEnabled = isEnabled,
                priority = priority,
                tags = tags,
                description = description,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

        companion object {
            fun fromEntity(entity: CustomPromptEntity) =
                CustomPromptBackupEntry(
                    id = entity.id,
                    name = entity.name,
                    content = entity.content,
                    type = entity.type,
                    position = entity.position,
                    isEnabled = entity.isEnabled,
                    priority = entity.priority,
                    tags = entity.tags,
                    description = entity.description,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                )
        }
    }

    /** 当前快照包含全部 12 张业务表（导出采集 / 恢复解码共用结构）。 */
    private data class Tables(
        val characters: List<CharacterEntity> = emptyList(),
        val sessions: List<SessionEntity> = emptyList(),
        val messages: List<MessageEntity> = emptyList(),
        val memories: List<MemoryEntity> = emptyList(),
        val worldBooks: List<WorldBookEntity> = emptyList(),
        val worldBookEntries: List<WorldBookEntryEntity> = emptyList(),
        val settings: List<SettingsBackupEntry> = emptyList(),
        val structuredMemories: List<StructuredMemoryEntity> = emptyList(),
        val vectorMemories: List<VectorMemoryEntity> = emptyList(),
        val themePacks: List<ThemePackEntity> = emptyList(),
        val apiConfigs: List<ApiConfigBackupEntry> = emptyList(),
        val customPrompts: List<CustomPromptBackupEntry> = emptyList(),
        val decodeWarnings: List<String> = emptyList(),
    )

    companion object {
        const val FORMAT_VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val CHARACTERS = "characters.json"
        private const val SESSIONS = "sessions.json"
        private const val MESSAGES = "messages.json"
        private const val MEMORIES = "memories.json"
        private const val WORLD_BOOKS = "world_books.json"
        private const val WORLD_BOOK_ENTRIES = "world_book_entries.json"
        private const val SETTINGS = "settings.json"
        private const val STRUCTURED_MEMORY = "structured_memory.json"
        private const val VECTOR_MEMORY = "vector_memory.json"
        private const val THEME_PACKS = "theme_packs.json"
        private const val API_CONFIGS = "api_configs.json"
        private const val CUSTOM_PROMPTS = "custom_prompts.json"
        internal const val VECTOR_STORE_FILE = "vector_store.json"
        internal const val THEMES_DIR_PREFIX = "themes/"
        private const val MAX_BACKUP_ENTRIES = 4096
        private const val MAX_BACKUP_ENTRY_BYTES = 32L * 1024L * 1024L
        private const val MAX_BACKUP_TOTAL_BYTES = 128L * 1024L * 1024L
        private val SAFE_THEME_PACK_ID = Regex("[A-Za-z0-9._-]{1,128}")

        internal val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        internal fun endpointForRestore(endpoint: String): String =
            if (ApiEndpointPolicy.writeRejectionReason(endpoint) == null) endpoint else ""

        // ---- 纯函数（JVM 可测）：清单校验与容错列表解码 ----

        /**
         * 校验并解析备份清单；格式不符抛 IllegalArgumentException（中文消息直出 UI）。
         * 广捕异常：解析边界把任何底层异常统一转换为用户可懂的恢复提示，原始异常类型无意义。
         * 注意 kotlinx SerializationException 继承 IllegalArgumentException——解码必须独立 try，
         * 否则解析失败会被"校验分支"的 catch 原样上抛，用户看到的是底层英文报错。
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun parseManifest(text: String): BackupManifest {
            val m =
                try {
                    json.decodeFromString(BackupManifest.serializer(), text)
                } catch (e: Exception) {
                    throw IllegalArgumentException("备份清单解析失败：不是有效的 Mistbell 备份文件（${e.message ?: "格式错误"}）")
                }
            validateFormat(m.format)
            return m
        }

        /** 备份格式版本校验：高于支持版本提示升级，非法值提示文件损坏（require → IllegalArgumentException） */
        private fun validateFormat(format: Int) {
            require(format <= FORMAT_VERSION) { "备份格式（v$format）高于当前应用支持的 v$FORMAT_VERSION，请升级应用后再恢复" }
            require(format >= 1) { "备份清单格式非法（format=$format），文件可能已损坏" }
        }

        /**
         * 容错列表解码：整体按数组逐元素解码，坏元素跳过而不是整表丢弃——
         * 恢复是数据抢救场景，单条损坏不应牺牲其余全部数据。
         * 任何异常都按"该表无可恢复数据"处理（空表是安全缺省，错误本身已在恢复入口校验清单时把关）。
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun <T> decodeList(
            text: String?,
            serializer: KSerializer<T>,
        ): List<T> =
            if (text.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    val array = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
                    array.mapNotNull { element ->
                        runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()
                    }
                }.getOrDefault(emptyList())
            }

        /**
         * Restore-time decoding: reject a broken whole-table payload, but keep the legacy
         * per-row salvage behavior when at least one record remains decodable.
         */
        internal fun <T> decodeBackupList(
            files: Map<String, String>,
            manifest: BackupManifest,
            entryName: String,
            serializer: KSerializer<T>,
            warnings: MutableList<String>? = null,
        ): List<T> {
            val text = files[entryName]
            val expectedCount = manifest.counts[entryName]
            if (text == null) {
                require(expectedCount == null || expectedCount == 0) {
                    "备份文件损坏：清单声明 $entryName 有 $expectedCount 条数据，但缺少该文件"
                }
                return emptyList()
            }
            val array =
                try {
                    json.parseToJsonElement(text) as? JsonArray
                        ?: throw IllegalArgumentException("不是 JSON 数组")
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("备份文件损坏：$entryName 无法解析为 JSON 数组", e)
                }
            val decoded = decodeList(text, serializer)
            if (decoded.size < array.size) {
                warnings?.add("备份表 $entryName 有 ${array.size - decoded.size} 条记录无法解析，已跳过")
            }
            if (expectedCount != null && expectedCount != array.size) {
                warnings?.add("备份清单中 $entryName 声明 $expectedCount 条，文件实际包含 ${array.size} 条")
            }
            require(expectedCount == null || expectedCount == 0 || decoded.isNotEmpty()) {
                "备份文件损坏：清单声明 $entryName 有 $expectedCount 条数据，但没有任何可恢复记录"
            }
            return decoded
        }

        /** settings 导出条目：密文解为明文并标记 sensitive（明文原样直通） */
        fun toBackupEntry(entity: SettingsEntity): SettingsBackupEntry {
            val encrypted = SecureStore.isEncrypted(entity.value)
            check(!entity.value.startsWith(SecureStore.PREFIX) || encrypted) {
                "设置 ${entity.key} 的加密格式损坏，已中止备份以避免丢失敏感数据"
            }
            val sensitive = encrypted || isSensitiveSettingKey(entity.key)
            val value =
                if (encrypted) {
                    requireDecryptedSecretValue(entity.key, SecureStore.unwrap(entity.value))
                } else {
                    entity.value
                }
            return SettingsBackupEntry(
                key = entity.key,
                value = value,
                sensitive = sensitive,
            )
        }

        internal fun requireDecryptedSecretValue(
            key: String,
            value: String,
        ): String =
            value.also { decrypted ->
                check(decrypted.isNotEmpty()) {
                    "无法解密设置 $key，已中止备份以避免丢失敏感数据"
                }
            }

        /** Treat credential-bearing keys as sensitive even when importing an untrusted backup. */
        internal fun isSensitiveSettingKey(key: String): Boolean = SecureStore.isSensitiveKey(key)

        /** settings 恢复条目：敏感值必须重新加密，不能信任备份中的 sensitive 标志。 */
        fun toSettingsEntity(entry: SettingsBackupEntry): SettingsEntity =
            SettingsEntity(
                entry.key,
                if (entry.sensitive || isSensitiveSettingKey(entry.key)) {
                    SecureStore.wrap(entry.value)
                } else {
                    entry.value
                },
            )

        internal fun validateThemePackId(id: String) {
            require(id != "." && id != ".." && SAFE_THEME_PACK_ID.matches(id)) {
                "备份文件非法：主题包 ID 包含不安全的路径字符"
            }
        }

        internal fun validateBackupEntryPath(
            name: String,
            isDirectory: Boolean,
        ) {
            val path = if (isDirectory) name.removeSuffix("/") else name
            require(path.isNotBlank() && !path.startsWith("/") && '\\' !in path) {
                "备份文件非法：条目路径格式不安全"
            }
            require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "备份文件非法：条目路径包含不安全的路径段"
            }
        }
    }

    // ---- 导出 ----

    suspend fun exportToUri(uri: Uri): BackupSummary =
        withContext(Dispatchers.IO) {
            val tables = collectTables()
            val counts = tableCounts(tables)
            val manifest =
                BackupManifest(
                    format = FORMAT_VERSION,
                    appVersion = com.mistbell.tavern.android.BuildConfig.VERSION_NAME,
                    createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                    counts = counts,
                )

            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    zip.putJsonEntry(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest))
                    zip.putJsonEntry(CHARACTERS, encodeList(tables.characters, CharacterEntity.serializer()))
                    zip.putJsonEntry(SESSIONS, encodeList(tables.sessions, SessionEntity.serializer()))
                    zip.putJsonEntry(MESSAGES, encodeList(tables.messages, MessageEntity.serializer()))
                    zip.putJsonEntry(MEMORIES, encodeList(tables.memories, MemoryEntity.serializer()))
                    zip.putJsonEntry(WORLD_BOOKS, encodeList(tables.worldBooks, WorldBookEntity.serializer()))
                    zip.putJsonEntry(
                        WORLD_BOOK_ENTRIES,
                        encodeList(tables.worldBookEntries, WorldBookEntryEntity.serializer()),
                    )
                    zip.putJsonEntry(SETTINGS, encodeList(tables.settings, SettingsBackupEntry.serializer()))
                    zip.putJsonEntry(
                        STRUCTURED_MEMORY,
                        encodeList(tables.structuredMemories, StructuredMemoryEntity.serializer()),
                    )
                    zip.putJsonEntry(VECTOR_MEMORY, encodeList(tables.vectorMemories, VectorMemoryEntity.serializer()))
                    zip.putJsonEntry(THEME_PACKS, encodeList(tables.themePacks, ThemePackEntity.serializer()))
                    zip.putJsonEntry(API_CONFIGS, encodeList(tables.apiConfigs, ApiConfigBackupEntry.serializer()))
                    zip.putJsonEntry(
                        CUSTOM_PROMPTS,
                        encodeList(tables.customPrompts, CustomPromptBackupEntry.serializer()),
                    )

                    // 主题包资产与向量库文件（可选条目；缺失不报错）
                    tables.themePacks.forEach { pack -> putThemeAssets(zip, context, pack) }
                    File(context.filesDir, VECTOR_STORE_FILE).takeIf { it.exists() }?.let { f ->
                        zip.putNextEntry(ZipEntry(VECTOR_STORE_FILE))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalArgumentException("备份失败：无法写入所选位置")

            BackupSummary(added = counts)
        }

    private suspend fun collectTables(): Tables {
        val characters = db.characterDao().getAllOnce()
        val sessions = db.sessionDao().getAllOnce()
        val messages = db.messageDao().getAllOnce()
        val memories = db.memoryDao().getAllOnce()
        val worldBooks = db.worldBookDao().getAllBooksOnce()
        val worldBookEntries = worldBooks.flatMap { db.worldBookDao().getEntriesList(it.id) }
        val settings = db.settingsDao().getAll().first().map { toBackupEntry(it) }
        return Tables(
            characters = characters,
            sessions = sessions,
            messages = messages,
            memories = memories,
            worldBooks = worldBooks,
            worldBookEntries = worldBookEntries,
            settings = settings,
            structuredMemories = db.structuredMemoryDao().getAllOnce(),
            vectorMemories = db.vectorMemoryDao().getAllOnce(),
            themePacks = db.themePackDao().observeAll().first(),
            apiConfigs = db.apiConfigDao().getAll().map(ApiConfigBackupEntry::fromEntity),
            customPrompts = db.customPromptDao().getAll().map(CustomPromptBackupEntry::fromEntity),
        )
    }

    // ---- 恢复（合并语义：已存在主键跳过，不删本地数据） ----

    suspend fun restoreFromUri(uri: Uri): BackupSummary =
        withContext(Dispatchers.IO) {
            val files = readBackupZip(uri)
            val manifestText =
                files[MANIFEST]
                    ?: throw IllegalArgumentException("恢复失败：备份中缺少 manifest.json，不是有效的 Mistbell 备份")
            val manifest = parseManifest(manifestText)

            val tables = decodeTables(files, manifest)
            tables.themePacks.forEach { validateThemePackId(it.id) }
            val summary = mergeTables(tables)

            // 文件写入在事务提交之后（文件系统不可回滚，不参与原子性）：
            // 主题包资产仅在目录缺失时铺入；向量库仅当本地不存在时铺入（避免覆盖更健康的本地索引）。
            //
            // ⚠️ 走到这里数据库**已经提交**，所以资材写入失败**不能**当作整次恢复失败：
            // 那会让调用方报「恢复失败」，而实际上数据已完整合并进库——用户会以为白恢复了。
            // 因此降级为 warning，让 UI 如实呈现「数据已恢复，但部分资材未写入」。
            val assetWarnings =
                try {
                    writeAuxiliaryFiles(files, tables.themePacks)
                    emptyList()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Keep untrusted file paths/URLs from an imported backup out of UI messages and logs.
                    android.util.Log.w("BackupManager", "Auxiliary restore failed (${error.javaClass.simpleName})")
                    listOf("数据已恢复，但部分资材文件未写入；请检查附件是否完整。")
                }
            summary.copy(warnings = tables.decodeWarnings + assetWarnings)
        }

    private fun decodeTables(
        files: Map<String, String>,
        manifest: BackupManifest,
    ): Tables {
        val decodeWarnings = mutableListOf<String>()

        fun <T> decode(
            entryName: String,
            serializer: KSerializer<T>,
        ): List<T> = decodeBackupList(files, manifest, entryName, serializer, decodeWarnings)

        val decodedApiConfigs =
            requireUniqueBackupIds(
                decode(API_CONFIGS, ApiConfigBackupEntry.serializer()),
                API_CONFIGS,
            ) { it.id }
        val quarantinedApiConfigCount =
            decodedApiConfigs.count { ApiEndpointPolicy.writeRejectionReason(it.apiUrl) != null }
        if (quarantinedApiConfigCount > 0) {
            decodeWarnings.add(
                "备份中有 $quarantinedApiConfigCount 个 API 地址不符合当前网络安全策略；" +
                    "导入这些配置时将清空地址以防止不安全请求，请在 API 配置中重新填写。",
            )
        }

        return Tables(
            characters = decode(CHARACTERS, CharacterEntity.serializer()),
            sessions = decode(SESSIONS, SessionEntity.serializer()),
            messages = decode(MESSAGES, MessageEntity.serializer()),
            memories = decode(MEMORIES, MemoryEntity.serializer()),
            worldBooks = decode(WORLD_BOOKS, WorldBookEntity.serializer()),
            worldBookEntries = decode(WORLD_BOOK_ENTRIES, WorldBookEntryEntity.serializer()),
            settings = decode(SETTINGS, SettingsBackupEntry.serializer()),
            structuredMemories = decode(STRUCTURED_MEMORY, StructuredMemoryEntity.serializer()),
            vectorMemories = decode(VECTOR_MEMORY, VectorMemoryEntity.serializer()),
            themePacks = decode(THEME_PACKS, ThemePackEntity.serializer()),
            apiConfigs = decodedApiConfigs,
            customPrompts =
                requireUniqueBackupIds(
                    decode(CUSTOM_PROMPTS, CustomPromptBackupEntry.serializer()),
                    CUSTOM_PROMPTS,
                ) { it.id },
            decodeWarnings = decodeWarnings,
        )
    }

    /** 全部表在同一事务合并：要么全部成功，要么回到恢复前状态 */
    private suspend fun mergeTables(t: Tables): BackupSummary {
        val added = mutableMapOf<String, Int>()
        val skipped = mutableMapOf<String, Int>()
        db.withTransaction {
            val (newCharacters, dupCharacters) = t.characters.partition { db.characterDao().getById(it.id) == null }
            db.characterDao().upsertAll(newCharacters)
            added[CHARACTERS] = newCharacters.size
            skipped[CHARACTERS] = dupCharacters.size

            val (newSessions, dupSessions) = t.sessions.partition { db.sessionDao().getById(it.id) == null }
            newSessions.forEach { db.sessionDao().upsert(it) }
            added[SESSIONS] = newSessions.size
            skipped[SESSIONS] = dupSessions.size

            val (newMessages, dupMessages) =
                t.messages.partition { db.messageDao().getById(it.id, it.sessionId, it.ownerId) == null }
            db.messageDao().upsertAll(newMessages)
            added[MESSAGES] = newMessages.size
            skipped[MESSAGES] = dupMessages.size

            val (newMemories, dupMemories) = t.memories.partition { db.memoryDao().getById(it.id) == null }
            db.memoryDao().upsertAll(newMemories)
            added[MEMORIES] = newMemories.size
            skipped[MEMORIES] = dupMemories.size

            val (newBooks, dupBooks) = t.worldBooks.partition { db.worldBookDao().getById(it.id) == null }
            newBooks.forEach { db.worldBookDao().upsertBook(it) }
            added[WORLD_BOOKS] = newBooks.size
            skipped[WORLD_BOOKS] = dupBooks.size

            val (newEntries, dupEntries) =
                t.worldBookEntries.partition { db.worldBookDao().getEntryById(it.id) == null }
            db.worldBookDao().upsertEntries(newEntries)
            added[WORLD_BOOK_ENTRIES] = newEntries.size
            skipped[WORLD_BOOK_ENTRIES] = dupEntries.size

            // settings：备份值显式胜出（用户点了"恢复"即表达此意图）；敏感值重加密
            t.settings.forEach { db.settingsDao().upsert(toSettingsEntity(it)) }
            added[SETTINGS] = t.settings.size

            val (newStructured, dupStructured) =
                t.structuredMemories.partition { db.structuredMemoryDao().getById(it.id) == null }
            newStructured.forEach { db.structuredMemoryDao().insert(it) }
            added[STRUCTURED_MEMORY] = newStructured.size
            skipped[STRUCTURED_MEMORY] = dupStructured.size

            val (newVector, dupVector) = t.vectorMemories.partition { db.vectorMemoryDao().getById(it.id) == null }
            newVector.forEach { db.vectorMemoryDao().insert(it) }
            added[VECTOR_MEMORY] = newVector.size
            skipped[VECTOR_MEMORY] = dupVector.size

            val (newPacks, dupPacks) = t.themePacks.partition { db.themePackDao().getById(it.id) == null }
            newPacks.forEach { db.themePackDao().upsert(it) }
            added[THEME_PACKS] = newPacks.size
            skipped[THEME_PACKS] = dupPacks.size

            val (newApiConfigs, dupApiConfigs) = t.apiConfigs.partition { db.apiConfigDao().getById(it.id) == null }
            db.apiConfigDao().insertAll(newApiConfigs.map(ApiConfigBackupEntry::toEntity))
            added[API_CONFIGS] = newApiConfigs.size
            skipped[API_CONFIGS] = dupApiConfigs.size

            val (newCustomPrompts, dupCustomPrompts) =
                t.customPrompts.partition { db.customPromptDao().getById(it.id) == null }
            db.customPromptDao().insertAll(newCustomPrompts.map(CustomPromptBackupEntry::toEntity))
            added[CUSTOM_PROMPTS] = newCustomPrompts.size
            skipped[CUSTOM_PROMPTS] = dupCustomPrompts.size
        }
        return BackupSummary(added = added, skipped = skipped.filterValues { it > 0 })
    }

    private fun writeAuxiliaryFiles(
        files: Map<String, String>,
        themePacks: List<ThemePackEntity>,
    ) {
        themePacks.forEach { pack -> writeThemeAssetsIfAbsent(context, pack, files) }
        val vectorStoreEntry = files[VECTOR_STORE_FILE] ?: return
        val target = File(context.filesDir, VECTOR_STORE_FILE)
        if (!target.exists()) {
            writeBytesAtomically(target, vectorStoreEntry.toByteArray(Charsets.ISO_8859_1))
        }
    }

    // ---- zip 工具 ----

    private fun tableCounts(t: Tables): Map<String, Int> =
        mapOf(
            CHARACTERS to t.characters.size,
            SESSIONS to t.sessions.size,
            MESSAGES to t.messages.size,
            MEMORIES to t.memories.size,
            WORLD_BOOKS to t.worldBooks.size,
            WORLD_BOOK_ENTRIES to t.worldBookEntries.size,
            SETTINGS to t.settings.size,
            STRUCTURED_MEMORY to t.structuredMemories.size,
            VECTOR_MEMORY to t.vectorMemories.size,
            THEME_PACKS to t.themePacks.size,
            API_CONFIGS to t.apiConfigs.size,
            CUSTOM_PROMPTS to t.customPrompts.size,
        )

    /**
     * 读取备份 zip 全部条目（文本按 UTF-8；主题资产/向量文件按 ISO-8859-1 保字节的字符串承载，
     * 写回时原样还原字节）。Entry 名含 ".." 一律拒绝（防路径穿越，与 ThemePackRepository 同规）。
     */
    private fun readBackupZip(uri: Uri): Map<String, String> {
        val files = mutableMapOf<String, String>()
        var totalBytes = 0L
        var entryCount = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    require(entryCount <= MAX_BACKUP_ENTRIES) {
                        "备份文件非法：条目数量超出限制"
                    }
                    val currentEntry = entry
                    val remainingBytes = MAX_BACKUP_TOTAL_BYTES - totalBytes
                    require(remainingBytes > 0) { "备份文件过大，已超出解压大小限制" }
                    val bytes = readBoundedEntry(zip, minOf(MAX_BACKUP_ENTRY_BYTES, remainingBytes))
                    totalBytes += bytes.size
                    storeEntry(files, currentEntry, bytes)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("恢复失败：无法打开所选文件")
        return files
    }
}

// ---- 文件级私有工具（置类外避免触发 TooManyFunctions，与 SettingsRepository 先例一致） ----

private fun <T> encodeList(
    list: List<T>,
    serializer: KSerializer<T>,
): String = BackupManager.json.encodeToString(ListSerializer(serializer), list)

private fun ZipOutputStream.putJsonEntry(
    name: String,
    content: String,
) {
    putNextEntry(ZipEntry(name))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun putThemeAssets(
    zip: ZipOutputStream,
    context: Context,
    pack: ThemePackEntity,
) {
    BackupManager.validateThemePackId(pack.id)
    val dir = File(context.filesDir, "themes/${pack.id}")
    dir.listFiles()?.forEach { f ->
        zip.putNextEntry(ZipEntry("${BackupManager.THEMES_DIR_PREFIX}${pack.id}/${f.name}"))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}

private fun writeThemeAssetsIfAbsent(
    context: Context,
    pack: ThemePackEntity,
    files: Map<String, String>,
) {
    BackupManager.validateThemePackId(pack.id)
    val dir = File(context.filesDir, "themes/${pack.id}")
    if (!dir.exists()) {
        check(dir.mkdirs() || dir.isDirectory) { "无法创建主题资源目录" }
    }
    check(dir.isDirectory) { "主题资源路径不是目录" }
    val prefix = "${BackupManager.THEMES_DIR_PREFIX}${pack.id}/"
    files.forEach { (name, content) ->
        if (name.startsWith(prefix)) writeThemeAssetIfMissing(dir, name, content)
    }
}

private fun writeThemeAssetIfMissing(
    dir: File,
    name: String,
    content: String,
) {
    val fileName = name.substringAfterLast('/')
    if (fileName.isBlank()) return
    val target = File(dir, fileName)
    if (!target.exists()) writeBytesAtomically(target, content.toByteArray(Charsets.ISO_8859_1))
}

@Suppress("ThrowsCount")
private fun writeBytesAtomically(
    target: File,
    bytes: ByteArray,
) {
    val atomicFile = AtomicFile(target)
    var output: java.io.FileOutputStream? = null
    try {
        output = atomicFile.startWrite()
        output.write(bytes)
        atomicFile.finishWrite(output)
    } catch (e: java.io.IOException) {
        output?.let { runCatching { atomicFile.failWrite(it) } }
        throw e
    } catch (e: SecurityException) {
        output?.let { runCatching { atomicFile.failWrite(it) } }
        throw e
    } catch (e: IllegalStateException) {
        output?.let { runCatching { atomicFile.failWrite(it) } }
        throw e
    }
}

private fun readBoundedEntry(
    zip: ZipInputStream,
    maxBytes: Long,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = zip.read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "备份文件过大：单个条目超出解压大小限制" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun storeEntry(
    files: MutableMap<String, String>,
    entry: ZipEntry,
    bytes: ByteArray,
) {
    val name = entry.name
    BackupManager.validateBackupEntryPath(name, entry.isDirectory)
    if (entry.isDirectory) return
    require(!files.containsKey(name)) { "备份文件非法：存在重复条目 $name" }
    files[name] =
        if (name.startsWith(BackupManager.THEMES_DIR_PREFIX) || name == BackupManager.VECTOR_STORE_FILE) {
            String(bytes, Charsets.ISO_8859_1)
        } else {
            String(bytes, Charsets.UTF_8)
        }
}

/** Add the config ID to Keystore failures so a failed restore is diagnosable. */
internal fun wrapRestoredApiKey(
    id: String,
    plainApiKey: String,
    wrap: (String) -> String,
): String =
    try {
        wrap(plainApiKey)
    } catch (e: IllegalStateException) {
        throw IllegalStateException("无法加密备份中 API 配置 $id 的密钥，已中止恢复", e)
    }

/** Reject ambiguous duplicate primary keys in imported tables before the merge transaction. */
internal fun <T> requireUniqueBackupIds(
    entries: List<T>,
    entryName: String,
    idOf: (T) -> String,
): List<T> {
    val seen = HashSet<String>()
    require(entries.all { seen.add(idOf(it)) }) {
        "备份文件损坏：$entryName 存在重复 ID"
    }
    return entries
}
