package com.mistbell.tavern.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mistbell.tavern.android.data.local.dao.*
import com.mistbell.tavern.android.data.local.entity.*

@Database(
    entities = [
        CharacterEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        WorldBookEntity::class,
        WorldBookEntryEntity::class,
        SettingsEntity::class,
        PendingSyncEntity::class,
        StructuredMemoryEntity::class,
        VectorMemoryEntity::class,
        ThemePackEntity::class,
        ApiConfigEntity::class,
        CustomPromptEntity::class,
    ],
    version = 27,
    // schema 导出到 app/schemas/，Room 编译期校验 + 迁移测试基线（ROADMAP"防静默清库"）
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao

    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun worldBookDao(): WorldBookDao

    abstract fun settingsDao(): SettingsDao

    // M2 清创：MemoryDao 仅保留 deleteBySession/deleteAll（会话删除时的数据清理）
    abstract fun memoryDao(): MemoryDao

    abstract fun structuredMemoryDao(): StructuredMemoryDao

    abstract fun vectorMemoryDao(): VectorMemoryDao

    abstract fun themePackDao(): ThemePackDao

    abstract fun apiConfigDao(): ApiConfigDao

    abstract fun customPromptDao(): CustomPromptDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // 修复5：各迁移实际执行的 SQL 提取为 internal 常量，迁移对象与 JVM 迁移对齐测试
        // （MigrationSchemaAlignmentTest）引用【同一份】常量——此前测试断言的是测试文件里
        // 手工拼接的 DDL 副本，迁移被改坏测试照样绿（自证恒真）。改迁移必须改这里，测试随之校验。

        internal val MIGRATION_3_4_SQL: List<String> =
            listOf(
                // DEFAULT 必须与 SessionEntity 的 @ColumnInfo(defaultValue = "0") 一致
                "ALTER TABLE sessions ADD COLUMN enable_long_term_memory INTEGER NOT NULL DEFAULT 0",
            )

        internal val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_3_4_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_4_5_SQL: List<String> =
            listOf(
                // 整表重建：session_id 不带 SQL 默认值（实体未声明 defaultValue），旧行以 '' 回填
                """
                CREATE TABLE IF NOT EXISTS memories_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    character_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    layer TEXT NOT NULL,
                    type TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    `object` TEXT NOT NULL,
                    content TEXT NOT NULL,
                    importance REAL NOT NULL,
                    stability REAL NOT NULL,
                    status TEXT NOT NULL,
                    access_count INTEGER NOT NULL,
                    tags TEXT NOT NULL,
                    aliases TEXT NOT NULL
                )
                """.trimIndent(),
                """
                INSERT INTO memories_new (
                    id, owner_id, character_id, session_id, layer, type, subject, relation,
                    `object`, content, importance, stability, status, access_count, tags, aliases
                )
                SELECT
                    id, owner_id, character_id, '', layer, type, subject, relation,
                    `object`, content, importance, stability, status, access_count, tags, aliases
                FROM memories
                """.trimIndent(),
                "DROP TABLE memories",
                "ALTER TABLE memories_new RENAME TO memories",
            )

        internal val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_4_5_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_5_6_SQL: List<String> =
            listOf(
                "ALTER TABLE sessions ADD COLUMN context_token_limit INTEGER NOT NULL DEFAULT 4096",
            )

        internal val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_5_6_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_6_7_SQL: List<String> =
            listOf(
                "ALTER TABLE sessions ADD COLUMN participant_character_ids_json TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_6_7_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_7_8_SQL: List<String> = emptyList()

        internal val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 空迁移：仅版本号升级
                }
            }

        internal val MIGRATION_8_9_SQL: List<String> =
            listOf(
                // 性能索引（注意：短名——v14 修正为 Room 默认全列名）
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_updated ON sessions(owner_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_pinned ON sessions(owner_id, is_pinned, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_character ON sessions(owner_id, character_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_messages_session_created ON messages(session_id, created_at)",
                "CREATE INDEX IF NOT EXISTS index_messages_session_owner" +
                    " ON messages(session_id, owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_importance_created" +
                    " ON structured_memory(owner_id, importance, created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_session" +
                    " ON structured_memory(owner_id, session_id, created_at)",
            )

        internal val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_8_9_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_9_10_SQL: List<String> =
            listOf(
                // T1 主题包：新建 theme_packs 表 + characters.theme_id
                """
                CREATE TABLE IF NOT EXISTS theme_packs (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    author TEXT NOT NULL,
                    version TEXT NOT NULL,
                    tokens_json TEXT NOT NULL,
                    background_file TEXT,
                    created_at TEXT NOT NULL
                )
                """.trimIndent(),
                "ALTER TABLE characters ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_9_10_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_10_11_SQL: List<String> =
            listOf(
                // 会话级主题
                "ALTER TABLE sessions ADD COLUMN theme_id TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_10_11_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_11_12_SQL: List<String> =
            listOf(
                // 会话附加指令
                "ALTER TABLE sessions ADD COLUMN author_note TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_11_12_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_12_13_SQL: List<String> =
            listOf(
                // F3-FTS 性能索引
                "CREATE INDEX IF NOT EXISTS index_messages_owner_character_created" +
                    " ON messages(owner_id, character_id, created_at)",
            )

        internal val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_12_13_SQL.forEach(db::execSQL)
                }
            }

        // v13→v14：修复索引名不匹配导致的升级崩溃（真机实证 2026-09）
        // MIGRATION_8_9 短名索引 ≠ 实体 @Index 默认全列名 → Room 校验要求索引全集合相等 → 崩溃
        internal val MIGRATION_13_14_SQL: List<String> =
            listOf(
                // messages
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_created_at ON messages(session_id, created_at)",
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_owner_id_character_id" +
                    " ON messages(session_id, owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_messages_owner_id_character_id_created_at" +
                    " ON messages(owner_id, character_id, created_at)",
                "DROP INDEX IF EXISTS index_messages_session_created",
                "DROP INDEX IF EXISTS index_messages_session_owner",
                "DROP INDEX IF EXISTS index_messages_owner_character_created",
                // sessions
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_updated_at ON sessions(owner_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_is_pinned_updated_at" +
                    " ON sessions(owner_id, is_pinned, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_character_id_updated_at" +
                    " ON sessions(owner_id, character_id, updated_at)",
                "DROP INDEX IF EXISTS index_sessions_owner_updated",
                "DROP INDEX IF EXISTS index_sessions_owner_pinned",
                "DROP INDEX IF EXISTS index_sessions_character",
                // structured_memory
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_character_id" +
                    " ON structured_memory(owner_id, character_id)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_memory_type ON structured_memory(memory_type)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_importance ON structured_memory(importance)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_created_at ON structured_memory(created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_importance_created_at" +
                    " ON structured_memory(owner_id, importance, created_at)",
                "CREATE INDEX IF NOT EXISTS index_structured_memory_owner_id_session_id_created_at" +
                    " ON structured_memory(owner_id, session_id, created_at)",
                "DROP INDEX IF EXISTS index_structured_memory_importance_created",
                "DROP INDEX IF EXISTS index_structured_memory_session",
            )

        internal val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_13_14_SQL.forEach(db::execSQL)
                }
            }

        // v14→v15：v13 创建的 owner_character_created 短名索引修正为实体默认名
        // （v14 修正了 v8_9 的短名索引但遗漏了 v13 新增的这个）
        internal val MIGRATION_14_15_SQL: List<String> =
            listOf(
                "CREATE INDEX IF NOT EXISTS index_messages_owner_id_character_id_created_at" +
                    " ON messages(owner_id, character_id, created_at)",
                "DROP INDEX IF EXISTS index_messages_owner_character_created",
            )

        internal val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_14_15_SQL.forEach(db::execSQL)
                }
            }

        // v15→v16：消息窗口分页（getLatestBySession/getOlderBySession）的性能索引。
        // 索引名必须与 Room 默认命名（index_<表>_<列连缀>）完全一致，列名用反引号包裹——
        // 名字不一致会复现 v14 的 "Migration didn't properly handle" 升级崩溃
        internal val MIGRATION_15_16_SQL: List<String> =
            listOf(
                "CREATE INDEX IF NOT EXISTS index_messages_session_id_owner_id_character_id_created_at" +
                    " ON messages(`session_id`, `owner_id`, `character_id`, `created_at`)",
                "CREATE INDEX IF NOT EXISTS index_messages_owner_id_session_id_created_at" +
                    " ON messages(`owner_id`, `session_id`, `created_at`)",
            )

        internal val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_15_16_SQL.forEach(db::execSQL)
                }
            }

        // v16→v17：会话模式骨架（MODES.md 决策：一次表达全部五档，未来加模式零迁移）。
        // 本批取值仅 classic|group（"narrator" 与④⑤档为骨架预留，界面不露出）；
        // DEFAULT 值必须与 SessionEntity 的 @ColumnInfo(defaultValue = "classic" / "") 逐字符一致
        internal val MIGRATION_16_17_SQL: List<String> =
            listOf(
                "ALTER TABLE sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'classic'",
                "ALTER TABLE sessions ADD COLUMN mode_config_json TEXT NOT NULL DEFAULT ''",
            )

        internal val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_16_17_SQL.forEach(db::execSQL)
                }
            }

        // v17→v18：会话级「长期记忆开关 / 上下文长度」从"建会话时快照全局默认"改为
        // 三态覆盖位（null = 跟随全局，显式值压过全局——SETTINGS.md 分层原则的落地）。
        // SQLite 不支持改列可空性 → 整表重建；旧行的具体值一律保留（语义 = 用户已显式设置，
        // 与全局脱钩），只有新会话才以 null 享受"跟随全局"。
        // 列可空性/默认值必须与 SessionEntity 注解逐列一致（Room TableInfo 全量比对），
        // 索引在 DROP TABLE 后随之消失，必须按 Room 默认名重建（v14 事故教训）
        internal val MIGRATION_17_18_SQL: List<String> =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS sessions_new (
                    id TEXT NOT NULL,
                    owner_id TEXT NOT NULL,
                    character_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    message_count INTEGER NOT NULL,
                    provider_id TEXT NOT NULL,
                    model_id TEXT NOT NULL,
                    world_book_id TEXT NOT NULL,
                    summary_json TEXT NOT NULL,
                    unread_count INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    pinned_at TEXT,
                    is_muted INTEGER NOT NULL DEFAULT 0,
                    enable_long_term_memory INTEGER,
                    context_token_limit INTEGER,
                    participant_character_ids_json TEXT NOT NULL DEFAULT '',
                    theme_id TEXT NOT NULL DEFAULT '',
                    author_note TEXT NOT NULL DEFAULT '',
                    mode TEXT NOT NULL DEFAULT 'classic',
                    mode_config_json TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(id, owner_id, character_id)
                )
                """.trimIndent(),
                """
                INSERT INTO sessions_new (
                    id, owner_id, character_id, title, created_at, updated_at, message_count,
                    provider_id, model_id, world_book_id, summary_json, unread_count, is_pinned,
                    pinned_at, is_muted, enable_long_term_memory, context_token_limit,
                    participant_character_ids_json, theme_id, author_note, mode, mode_config_json
                )
                SELECT
                    id, owner_id, character_id, title, created_at, updated_at, message_count,
                    provider_id, model_id, world_book_id, summary_json, unread_count, is_pinned,
                    pinned_at, is_muted, enable_long_term_memory, context_token_limit,
                    participant_character_ids_json, theme_id, author_note, mode, mode_config_json
                FROM sessions
                """.trimIndent(),
                "DROP TABLE sessions",
                "ALTER TABLE sessions_new RENAME TO sessions",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_updated_at ON sessions(owner_id, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_is_pinned_updated_at" +
                    " ON sessions(owner_id, is_pinned, updated_at)",
                "CREATE INDEX IF NOT EXISTS index_sessions_owner_id_character_id_updated_at" +
                    " ON sessions(owner_id, character_id, updated_at)",
            )

        @Suppress("MagicNumber") // 起止版本号即迁移语义本体，与 MIGRATION_16_17 等既往迁移同型
        internal val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_17_18_SQL.forEach(db::execSQL)
                }
            }

        // v18→v19：世界书条目新增 insert_position/depth 两列（插入位置功能此前只存在于 UI 表单，
        // 从未落库——v19 起 PromptBuilder 真正按位置/深度装配提示词）。
        // 纯加列迁移，列默认值须与 WorldBookEntryEntity 注解逐字符一致（Room TableInfo 比对）
        internal val MIGRATION_18_19_SQL: List<String> =
            listOf(
                "ALTER TABLE world_book_entries ADD COLUMN insert_position TEXT NOT NULL DEFAULT 'before_prompt'",
                "ALTER TABLE world_book_entries ADD COLUMN depth INTEGER NOT NULL DEFAULT 0",
            )

        @Suppress("MagicNumber") // 起止版本号即迁移语义本体，与 MIGRATION_16_17 等既往迁移同型
        internal val MIGRATION_18_19 =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_18_19_SQL.forEach(db::execSQL)
                }
            }

        // v19→v20：世界书条目新增 probability 列（触发概率，对齐酒馆——此前导入时被当未映射字段丢弃）。
        // 纯加列迁移，列默认值须与 WorldBookEntryEntity 注解逐字符一致（Room TableInfo 比对）
        internal val MIGRATION_19_20_SQL: List<String> =
            listOf(
                "ALTER TABLE world_book_entries ADD COLUMN probability REAL NOT NULL DEFAULT 1",
            )

        @Suppress("MagicNumber") // 起止版本号即迁移语义本体，与 MIGRATION_16_17 等既往迁移同型
        internal val MIGRATION_19_20 =
            object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_19_20_SQL.forEach(db::execSQL)
                }
            }

        // v20→v21：世界书条目新增 depth_role 列（@D 插入角色：system/user/assistant，对齐酒馆 role）。
        // 纯加列迁移，列默认值须与 WorldBookEntryEntity 注解逐字符一致（Room TableInfo 比对）
        internal val MIGRATION_20_21_SQL: List<String> =
            listOf(
                "ALTER TABLE world_book_entries ADD COLUMN depth_role TEXT NOT NULL DEFAULT 'system'",
            )

        @Suppress("MagicNumber") // 起止版本号即迁移语义本体，与 MIGRATION_16_17 等既往迁移同型
        internal val MIGRATION_20_21 =
            object : Migration(20, 21) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_20_21_SQL.forEach(db::execSQL)
                }
            }

        // v21→v22：新增 API 配置表和自定义提示词表
        internal val MIGRATION_21_22_SQL: List<String> =
            listOf(
                // 创建 API 配置表
                """
                CREATE TABLE IF NOT EXISTS api_configs (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    apiUrl TEXT NOT NULL,
                    apiKey TEXT NOT NULL,
                    model TEXT NOT NULL,
                    isDefault INTEGER NOT NULL,
                    lastTestStatus TEXT NOT NULL,
                    lastTestTime INTEGER NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
                // 创建自定义提示词表
                """
                CREATE TABLE IF NOT EXISTS custom_prompts (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    type TEXT NOT NULL,
                    position TEXT NOT NULL,
                    isEnabled INTEGER NOT NULL,
                    priority INTEGER NOT NULL,
                    tags TEXT NOT NULL,
                    description TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
                // 为 API 配置表创建索引
                "CREATE INDEX IF NOT EXISTS index_api_configs_isDefault ON api_configs(isDefault)",
                "CREATE INDEX IF NOT EXISTS index_api_configs_sortOrder ON api_configs(sortOrder)",
                // 为自定义提示词表创建索引
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_type ON custom_prompts(type)",
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_position ON custom_prompts(position)",
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_isEnabled ON custom_prompts(isEnabled)",
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_priority ON custom_prompts(priority)",
            )

        @Suppress("MagicNumber")
        internal val MIGRATION_21_22 =
            object : Migration(21, 22) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_21_22_SQL.forEach(db::execSQL)
                }
            }

        // v22→v23：api_configs 增加接口类型（type）与 1M 上下文开关（context1m）两列
        internal val MIGRATION_22_23_SQL: List<String> =
            listOf(
                "ALTER TABLE api_configs ADD COLUMN type TEXT NOT NULL DEFAULT 'openai'",
                "ALTER TABLE api_configs ADD COLUMN context1m INTEGER NOT NULL DEFAULT 0",
            )

        @Suppress("MagicNumber")
        internal val MIGRATION_22_23 =
            object : Migration(22, 23) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_22_23_SQL.forEach(db::execSQL)
                }
            }

        // v23→v24：流式传输开关从全局 settings 下沉到每张 API 配置卡。
        // 迁移时沿用旧全局开关，避免已有用户的流式偏好被静默改变。
        internal val MIGRATION_23_24_SQL: List<String> =
            listOf(
                "ALTER TABLE api_configs ADD COLUMN streamingEnabled INTEGER NOT NULL DEFAULT 1",
                "UPDATE api_configs SET streamingEnabled = CASE " +
                    "WHEN (SELECT value FROM settings WHERE `key` = 'streaming_enabled') = '0' " +
                    "THEN 0 ELSE 1 END",
            )

        @Suppress("MagicNumber")
        internal val MIGRATION_23_24 =
            object : Migration(23, 24) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_23_24_SQL.forEach(db::execSQL)
                }
            }

        internal val MIGRATION_24_25_SQL: List<String> =
            listOf(
                "ALTER TABLE world_book_entries ADD COLUMN secondary_keys_json TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE world_book_entries ADD COLUMN sticky INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE world_book_entries ADD COLUMN cooldown INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE world_book_entries ADD COLUMN delay INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE world_book_entries ADD COLUMN group_name TEXT NOT NULL DEFAULT ''",
            )

        @Suppress("MagicNumber")
        internal val MIGRATION_24_25 =
            object : Migration(24, 25) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_24_25_SQL.forEach(db::execSQL)
                }
            }

        // v25→v26：messages 新增 usage_json（真实 API usage 落库；NULL = 未知/未上报，不填 0）；
        // api_configs 新增 thinkingDisabled（思考模式开关）。
        // thinkingDisabled 的 DEFAULT 0 与 ApiConfigEntity 的 @ColumnInfo(defaultValue = "0") 一致。
        internal val MIGRATION_25_26_SQL: List<String> =
            listOf(
                "ALTER TABLE messages ADD COLUMN usage_json TEXT",
                "ALTER TABLE api_configs ADD COLUMN thinkingDisabled INTEGER NOT NULL DEFAULT 0",
            )

        @Suppress("MagicNumber")
        internal val MIGRATION_25_26 =
            object : Migration(25, 26) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_25_26_SQL.forEach(db::execSQL)
                }
            }

        // v26→v27：新增提示词快照，并统一 v26 新建数据库与历史迁移数据库的 api_configs schema。
        // v26 的 Room schema 未声明 type/context1m/streamingEnabled 默认值和部分索引；
        // v27 实体已声明它们，因此重建 api_configs 以对齐默认值，并补齐实体索引。
        internal val MIGRATION_26_27_SQL: List<String> =
            listOf(
                "ALTER TABLE messages ADD COLUMN prompt_context_snapshot_json TEXT NOT NULL DEFAULT ''",
                """
                CREATE TABLE IF NOT EXISTS api_configs_new (
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    apiUrl TEXT NOT NULL,
                    apiKey TEXT NOT NULL,
                    model TEXT NOT NULL,
                    isDefault INTEGER NOT NULL,
                    lastTestStatus TEXT NOT NULL,
                    lastTestTime INTEGER NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    type TEXT NOT NULL DEFAULT 'openai',
                    context1m INTEGER NOT NULL DEFAULT 0,
                    streamingEnabled INTEGER NOT NULL DEFAULT 1,
                    thinkingDisabled INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
                """
                INSERT INTO api_configs_new (
                    id, name, apiUrl, apiKey, model, isDefault, lastTestStatus,
                    lastTestTime, sortOrder, type, context1m, streamingEnabled,
                    thinkingDisabled, createdAt, updatedAt
                )
                SELECT
                    id, name, apiUrl, apiKey, model, isDefault, lastTestStatus,
                    lastTestTime, sortOrder, type, context1m, streamingEnabled,
                    thinkingDisabled, createdAt, updatedAt
                FROM api_configs
                """.trimIndent(),
                "DROP TABLE api_configs",
                "ALTER TABLE api_configs_new RENAME TO api_configs",
                "CREATE INDEX IF NOT EXISTS index_api_configs_isDefault ON api_configs(isDefault)",
                "CREATE INDEX IF NOT EXISTS index_api_configs_sortOrder ON api_configs(sortOrder)",
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_type ON custom_prompts(type)",
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_position ON custom_prompts(position)",
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_isEnabled ON custom_prompts(isEnabled)",
                "CREATE INDEX IF NOT EXISTS index_custom_prompts_priority ON custom_prompts(priority)",
            )

        @Suppress("MagicNumber")
        internal val MIGRATION_26_27 =
            object : Migration(26, 27) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_26_27_SQL.forEach(db::execSQL)
                }
            }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tavern.db",
                )
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                    )
                    // 不移除上面的任何迁移，也不加 fallbackToDestructiveMigration：
                    // 遇到未覆盖的版本 Room 会直接抛 IllegalStateException（明确失败），
                    // 而不是静默清空用户数据（ROADMAP"防静默清库"）。
                    .build()
                    .also { instance = it }
            }
        }
    }
}
