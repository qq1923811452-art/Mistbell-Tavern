package com.mistbell.tavern.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移数据保全测试（instrumented，需要真机/模拟器）。
 *
 * 模拟真实用户升级路径：手工创建旧版数据库 + 写入测试数据 → Room 打开并执行迁移 → 验证数据完整性。
 * 这是 v14 事故（索引名不匹配导致升级崩溃）的端到端防线。
 *
 * 运行: `gradlew :app:connectedDebugAndroidTest`（需连接设备）
 */
@RunWith(AndroidJUnit4::class)
class MigrationDataIntegrityTest {
    private lateinit var db: AppDatabase
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /**
     * 手工创建 v11 版数据库（有 theme_id 但没有 author_note，索引是 v8_9 短名——v14 崩溃的确切现场）。
     *
     * 修复4：Room 打开时会校验【全部实体表】（列/主键/索引全集合相等），此前只建 5 张表且
     * sessions 用单列主键、structured_memory 列集与实体不符 → 迁移尚未执行就 IllegalStateException。
     * 此处的 DDL 按"15.json 最终形态减去后续迁移变更"反推 v11 时点的每张表：
     *  - sessions：最终 20 列减去 author_note（v12 迁移新增），复合主键 (id, owner_id, character_id)；
     *  - memories：v4_5 重建后的最终形态；theme_packs：v9_10 创建后的最终形态；
     *  - characters/messages/world_books/world_book_entries/settings/pending_sync/
     *    structured_memory/vector_memory：3→11 无列变更，与最终形态一致；
     *  - 索引：v8_9 短名（sessions×3 / messages×2 / structured_memory×2，v13_14 迁移负责改名）+
     *    vector_memory 的 4 个实体默认名索引（表创建时即有，后续迁移不触碰）。
     */
    private fun createV11Database() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val raw = context.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null)

        // 设置版本号（Room 以 PRAGMA user_version 判断）
        raw.execSQL("PRAGMA user_version = 11")

        // ---- characters（v11 已有 theme_id，与最终形态一致） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS characters (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                description TEXT NOT NULL,
                personality TEXT NOT NULL,
                scenario TEXT NOT NULL,
                first_mes TEXT NOT NULL,
                mes_example TEXT NOT NULL,
                color TEXT NOT NULL,
                avatar_data TEXT NOT NULL,
                world_book_id TEXT NOT NULL,
                theme_id TEXT NOT NULL DEFAULT '',
                data_json TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )

        // ---- sessions（v11 时点：无 author_note，复合主键） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
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
                enable_long_term_memory INTEGER NOT NULL DEFAULT 0,
                context_token_limit INTEGER NOT NULL DEFAULT 4096,
                participant_character_ids_json TEXT NOT NULL DEFAULT '',
                theme_id TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(id, owner_id, character_id)
            )
            """.trimIndent(),
        )

        // ---- messages（与最终形态一致） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                owner_id TEXT NOT NULL,
                character_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                thinking TEXT,
                created_at TEXT NOT NULL,
                memory_ids_json TEXT NOT NULL,
                swipes_json TEXT NOT NULL,
                swipe_index INTEGER NOT NULL,
                thinking_swipes_json TEXT NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )

        // ---- memories（v4_5 重建后的最终形态，列均无 SQL 默认值） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT NOT NULL,
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
                aliases TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )

        // ---- world_books / world_book_entries（复合主键 (id, book_id)） ----
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS world_books (id TEXT NOT NULL, name TEXT NOT NULL, settings_json TEXT NOT NULL, PRIMARY KEY(id))",
        )
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS world_book_entries (
                id TEXT NOT NULL,
                book_id TEXT NOT NULL,
                comment TEXT NOT NULL,
                keys_json TEXT NOT NULL,
                content TEXT NOT NULL,
                constant INTEGER NOT NULL,
                disable INTEGER NOT NULL,
                `order` INTEGER NOT NULL,
                PRIMARY KEY(id, book_id)
            )
            """.trimIndent(),
        )

        // ---- settings ----
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS settings (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
        raw.execSQL("INSERT INTO settings (`key`, `value`) VALUES ('dark_mode', 'system')")

        // ---- pending_sync（自增主键） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_sync (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                action TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // ---- structured_memory（v11 与最终列形态一致；character_id/session_id 可空、无 SQL 默认值） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS structured_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                owner_id TEXT NOT NULL,
                character_id TEXT,
                session_id TEXT,
                memory_type TEXT NOT NULL,
                title TEXT,
                content TEXT NOT NULL,
                structured_data TEXT,
                importance INTEGER NOT NULL,
                tags TEXT,
                keywords TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_accessed_at TEXT,
                access_count INTEGER NOT NULL,
                related_message_ids TEXT,
                source_type TEXT NOT NULL
            )
            """.trimIndent(),
        )

        // ---- vector_memory（与最终形态一致） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vector_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                owner_id TEXT NOT NULL,
                character_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                message_id TEXT,
                content TEXT NOT NULL,
                content_type TEXT NOT NULL,
                vector_id TEXT,
                embedding_model TEXT NOT NULL,
                importance_score REAL NOT NULL,
                token_count INTEGER,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )

        // ---- theme_packs（v9_10 创建后的形态） ----
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS theme_packs (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                author TEXT NOT NULL,
                version TEXT NOT NULL,
                tokens_json TEXT NOT NULL,
                background_file TEXT,
                created_at TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )

        // ---- 索引：v8_9 短名（v13_14/v14_15 迁移负责改为实体默认名——保留升级现场） ----
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_updated ON sessions(owner_id, updated_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_owner_pinned ON sessions(owner_id, is_pinned, updated_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_character ON sessions(owner_id, character_id, updated_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_created ON messages(session_id, created_at)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_owner ON messages(session_id, owner_id, character_id)")
        raw.execSQL(
            "CREATE INDEX IF NOT EXISTS index_structured_memory_importance_created" +
                " ON structured_memory(owner_id, importance, created_at)",
        )
        raw.execSQL(
            "CREATE INDEX IF NOT EXISTS index_structured_memory_session" +
                " ON structured_memory(owner_id, session_id, created_at)",
        )

        // vector_memory 的 4 个实体默认名索引（建表时即有，后续迁移不触碰——缺失会被 Room 打开校验拒绝）
        raw.execSQL(
            "CREATE INDEX IF NOT EXISTS index_vector_memory_owner_id_character_id_session_id" +
                " ON vector_memory(owner_id, character_id, session_id)",
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_vector_memory_message_id ON vector_memory(message_id)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_vector_memory_vector_id ON vector_memory(vector_id)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_vector_memory_created_at ON vector_memory(created_at)")

        // ---- 写入测试数据 ----
        raw.execSQL(
            """
            INSERT INTO characters (id, name, role, description, personality, scenario, first_mes, mes_example, color, avatar_data, world_book_id, theme_id, data_json)
            VALUES ('char-1', '测试角色', 'assistant', '描述', '性格', '场景', '你好！', '示例', '#007AFF', '', '', '', '{}')
            """.trimIndent(),
        )
        raw.execSQL(
            """
            INSERT INTO sessions (id, owner_id, character_id, title, provider_id, model_id, world_book_id, summary_json, unread_count, is_pinned, enable_long_term_memory, context_token_limit, participant_character_ids_json, theme_id, message_count, created_at, updated_at)
            VALUES ('sess-1', 'local-user', 'char-1', '测试会话', '', '', '', '', 0, 0, 1, 4096, '', 'theme-1', 3, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
            """.trimIndent(),
        )
        raw.execSQL(
            """
            INSERT INTO messages (id, session_id, owner_id, character_id, role, content, thinking, created_at, memory_ids_json, swipes_json, swipe_index, thinking_swipes_json, is_read)
            VALUES ('msg-1', 'sess-1', 'local-user', 'char-1', 'user', '你好', NULL, '2026-01-01T00:01:00Z', '', '', 0, '', 1)
            """.trimIndent(),
        )
        raw.execSQL(
            """
            INSERT INTO messages (id, session_id, owner_id, character_id, role, content, thinking, created_at, memory_ids_json, swipes_json, swipe_index, thinking_swipes_json, is_read)
            VALUES ('msg-2', 'sess-1', 'local-user', 'char-1', 'assistant', '你好！我是测试角色。', NULL, '2026-01-01T00:02:00Z', '', '', 0, '', 1)
            """.trimIndent(),
        )
        raw.execSQL(
            """
            INSERT INTO messages (id, session_id, owner_id, character_id, role, content, thinking, created_at, memory_ids_json, swipes_json, swipe_index, thinking_swipes_json, is_read)
            VALUES ('msg-3', 'sess-1', 'local-user', 'char-1', 'user', '再见', NULL, '2026-01-01T00:03:00Z', '', '', 0, '', 1)
            """.trimIndent(),
        )

        raw.close()
    }

    /** 打开数据库并执行生产迁移链，直达当前最新数据库版本 27。 */
    private fun openWithRoom(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.Companion.MIGRATION_3_4,
                AppDatabase.Companion.MIGRATION_4_5,
                AppDatabase.Companion.MIGRATION_5_6,
                AppDatabase.Companion.MIGRATION_6_7,
                AppDatabase.Companion.MIGRATION_7_8,
                AppDatabase.Companion.MIGRATION_8_9,
                AppDatabase.Companion.MIGRATION_9_10,
                AppDatabase.Companion.MIGRATION_10_11,
                AppDatabase.Companion.MIGRATION_11_12,
                AppDatabase.Companion.MIGRATION_12_13,
                AppDatabase.Companion.MIGRATION_13_14,
                AppDatabase.Companion.MIGRATION_14_15,
                AppDatabase.Companion.MIGRATION_15_16,
                AppDatabase.Companion.MIGRATION_16_17,
                AppDatabase.Companion.MIGRATION_17_18,
                AppDatabase.Companion.MIGRATION_18_19,
                AppDatabase.Companion.MIGRATION_19_20,
                AppDatabase.Companion.MIGRATION_20_21,
                AppDatabase.Companion.MIGRATION_21_22,
                AppDatabase.Companion.MIGRATION_22_23,
                AppDatabase.Companion.MIGRATION_23_24,
                AppDatabase.Companion.MIGRATION_24_25,
                AppDatabase.Companion.MIGRATION_25_26,
                AppDatabase.Companion.MIGRATION_26_27,
            )
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun migrateV11ToV21_preservesAllData() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // 验证角色
            val characters = db.characterDao().getAll().first()
            assertEquals("角色应保留", 1, characters.size)
            assertEquals("测试角色", characters[0].name)

            // 验证会话（theme_id 应保留）
            val sessions = db.sessionDao().getByCharacter("local-user", "char-1").first()
            assertTrue("会话应保留", sessions.isNotEmpty())
            assertEquals("theme-1", sessions[0].themeId)

            // 验证消息
            val messages =
                db.messageDao()
                    .getBySession("sess-1", "local-user")
                    .first()
            assertEquals("消息应全部保留", 3, messages.size)
            assertEquals("你好", messages[0].content)
            assertEquals("再见", messages[2].content)

            // 验证 v16 新增的两个分页索引已建立（名字必须与 Room 默认命名一致，否则打开校验即崩溃）
            db.openHelper.writableDatabase
                .query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN (?, ?)",
                    arrayOf(
                        "index_messages_session_id_owner_id_character_id_created_at",
                        "index_messages_owner_id_session_id_created_at",
                    ),
                ).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("v16 新增的两个分页索引都应存在", 2, cursor.getInt(0))
                }

            // 验证设置
            val darkMode = db.settingsDao().getValue("dark_mode")
            assertEquals("system", darkMode)
        }

    @Test
    fun migrateV11ToV21_canWriteAfterMigration() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // 迁移后写入新数据（验证 schema 可用）
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("post_migration_test", "ok"),
            )
            assertEquals("ok", db.settingsDao().getValue("post_migration_test"))

            // 写入新消息
            db.messageDao().upsert(
                com.mistbell.tavern.android.data.local.entity.MessageEntity(
                    id = "msg-new",
                    sessionId = "sess-1",
                    ownerId = "local-user",
                    characterId = "char-1",
                    role = "user",
                    content = "迁移后新消息",
                    thinking = null,
                    createdAt = "2026-09-01T00:00:00Z",
                    memoryIdsJson = "",
                    swipesJson = "",
                    swipeIndex = 0,
                    thinkingSwipesJson = "",
                ),
            )
            val messages =
                db.messageDao()
                    .getBySession("sess-1", "local-user")
                    .first()
            assertEquals("迁移后写入的新消息应在查询中", 4, messages.size)
        }

    @Test
    fun migrateV11ToV21_authorNoteColumnAdded() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // v12 添加了 author_note 列——应可写入和读取
            db.sessionDao().updateAuthorNote("sess-1", "local-user", "char-1", "这是一条附加指令")
            val session = db.sessionDao().get("sess-1", "local-user", "char-1")
            assertNotNull("会话应存在", session)
            assertEquals("这是一条附加指令", session?.authorNote)
        }

    @Test
    fun migrateV11ToV21_modeColumnAddedWithClassicDefault() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // v17 模式骨架：sessions.mode 列存在，PRAGMA dflt_value 应为带引号的 'classic'（与实体注解逐字符对齐）
            db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val dfltIdx = cursor.getColumnIndex("dflt_value")
                var modeFound = false
                var modeConfigFound = false
                while (cursor.moveToNext()) {
                    when (cursor.getString(nameIdx)) {
                        "mode" -> {
                            modeFound = true
                            assertEquals("mode 列默认值应为 'classic'", "'classic'", cursor.getString(dfltIdx))
                        }
                        "mode_config_json" -> {
                            modeConfigFound = true
                            assertEquals("mode_config_json 列默认值应为 ''", "''", cursor.getString(dfltIdx))
                        }
                    }
                }
                assertTrue("迁移后 sessions.mode 列应存在", modeFound)
                assertTrue("迁移后 sessions.mode_config_json 列应存在", modeConfigFound)
            }

            // 旧行经迁移回填后读取为 classic（SessionEntity.mode 默认值路径）
            val session = db.sessionDao().get("sess-1", "local-user", "char-1")
            assertNotNull("会话应存在", session)
            assertEquals("迁移后既有会话的 mode 应回填为 classic", "classic", session?.mode)
        }

    @Test
    fun migrateV11ToV21_overrideColumnsBecomeNullableAndPreserveValues() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // v18 三态覆盖位：两列应可空（notnull = 0）且无 SQL 默认值（与 SessionEntity 注解对齐）
            db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val notnullIdx = cursor.getColumnIndex("notnull")
                val dfltIdx = cursor.getColumnIndex("dflt_value")
                var ltmFound = false
                var ctxFound = false
                while (cursor.moveToNext()) {
                    when (cursor.getString(nameIdx)) {
                        "enable_long_term_memory" -> {
                            ltmFound = true
                            assertEquals("enable_long_term_memory 应可空", 0, cursor.getInt(notnullIdx))
                            assertEquals("enable_long_term_memory 不应有默认值", null, cursor.getString(dfltIdx))
                        }
                        "context_token_limit" -> {
                            ctxFound = true
                            assertEquals("context_token_limit 应可空", 0, cursor.getInt(notnullIdx))
                            assertEquals("context_token_limit 不应有默认值", null, cursor.getString(dfltIdx))
                        }
                    }
                }
                assertTrue("迁移后 enable_long_term_memory 列应存在", ltmFound)
                assertTrue("迁移后 context_token_limit 列应存在", ctxFound)
            }

            // 旧行 v11 写入的具体值（ltm=1, ctx=4096）必须原样保留——语义转为"用户已显式设置"
            val session = db.sessionDao().get("sess-1", "local-user", "char-1")
            assertNotNull("会话应存在", session)
            assertEquals("旧行的长期记忆开关值应保留", true, session?.enableLongTermMemory)
            assertEquals("旧行的上下文长度值应保留", 4096, session?.contextTokenLimit)

            // 三态可写：清除覆盖（写 null）后读取应为 null（跟随全局）
            db.sessionDao().updateLtmEnabled("sess-1", "local-user", "char-1", null)
            db.sessionDao().updateContextTokenLimit("sess-1", "local-user", "char-1", null)
            val cleared = db.sessionDao().get("sess-1", "local-user", "char-1")
            assertEquals("清除覆盖后应为 null（跟随全局）", null, cleared?.enableLongTermMemory)
            assertEquals("清除覆盖后应为 null（跟随全局）", null, cleared?.contextTokenLimit)
        }

    @Test
    fun migrateV11ToV21_worldBookPositionColumnsAdded() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // v19 世界书插入位置落库：两列存在，PRAGMA dflt_value 与实体注解逐字符对齐
            db.openHelper.writableDatabase.query("PRAGMA table_info(world_book_entries)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val dfltIdx = cursor.getColumnIndex("dflt_value")
                var posFound = false
                var depthFound = false
                var probabilityFound = false
                var depthRoleFound = false
                while (cursor.moveToNext()) {
                    when (cursor.getString(nameIdx)) {
                        "insert_position" -> {
                            posFound = true
                            assertEquals(
                                "insert_position 默认值应为 'before_prompt'",
                                "'before_prompt'",
                                cursor.getString(dfltIdx),
                            )
                        }
                        "depth" -> {
                            depthFound = true
                            assertEquals("depth 默认值应为 0", "0", cursor.getString(dfltIdx))
                        }
                        "probability" -> {
                            probabilityFound = true
                            assertEquals("probability 默认值应为 1", "1", cursor.getString(dfltIdx))
                        }
                        "depth_role" -> {
                            depthRoleFound = true
                            assertEquals(
                                "depth_role 默认值应为 'system'",
                                "'system'",
                                cursor.getString(dfltIdx),
                            )
                        }
                    }
                }
                assertTrue("迁移后 insert_position 列应存在", posFound)
                assertTrue("迁移后 depth 列应存在", depthFound)
                assertTrue("迁移后 probability 列应存在", probabilityFound)
                assertTrue("迁移后 depth_role 列应存在", depthRoleFound)
            }

            // 写入带位置/深度/概率/角色的条目后可原样读回（旧行由列默认值兜底为 before_prompt/0/1/system）
            db.worldBookDao().upsertBook(
                com.mistbell.tavern.android.data.local.entity.WorldBookEntity("wb-1", "测试书", "{}"),
            )
            db.worldBookDao().upsertEntries(
                listOf(
                    com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity(
                        id = "we-1",
                        bookId = "wb-1",
                        comment = "条目",
                        keysJson = "[]",
                        content = "内容",
                        constant = false,
                        disable = false,
                        order = 0,
                        insertPosition = "after_prompt",
                        depth = 3,
                        depthRole = "assistant",
                        probability = 0.5,
                    ),
                ),
            )
            val entries = db.worldBookDao().getEntriesList("wb-1")
            assertEquals(1, entries.size)
            assertEquals("after_prompt", entries[0].insertPosition)
            assertEquals(3, entries[0].depth)
            assertEquals("assistant", entries[0].depthRole)
            assertEquals(0.5, entries[0].probability, 0.0001)
        }

    @Test
    fun migrateV11ToV27_preservesMessagesAndAppliesV26V27ColumnDefaults() =
        runBlocking {
            createV11Database()
            db = openWithRoom()

            // Existing messages must survive v25→26→27 without inventing usage data.
            db.openHelper.writableDatabase
                .query(
                    "SELECT id, content, usage_json, prompt_context_snapshot_json FROM messages WHERE id IN (?, ?, ?) ORDER BY id",
                    arrayOf("msg-1", "msg-2", "msg-3"),
                ).use { cursor ->
                    assertEquals("三条旧消息均应保留", 3, cursor.count)
                    val idIdx = cursor.getColumnIndexOrThrow("id")
                    val contentIdx = cursor.getColumnIndexOrThrow("content")
                    val usageIdx = cursor.getColumnIndexOrThrow("usage_json")
                    val snapshotIdx = cursor.getColumnIndexOrThrow("prompt_context_snapshot_json")
                    val expectedContent =
                        mapOf(
                            "msg-1" to "你好",
                            "msg-2" to "你好！我是测试角色。",
                            "msg-3" to "再见",
                        )
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIdx)
                        assertEquals("旧消息内容不得改变：$id", expectedContent[id], cursor.getString(contentIdx))
                        assertTrue("旧消息 usage 未上报时必须保持 SQL NULL：$id", cursor.isNull(usageIdx))
                        assertEquals("旧消息的内部快照默认应为空字符串：$id", "", cursor.getString(snapshotIdx))
                    }
                }

            // Validate the migration's actual SQLite defaults, not just Kotlin constructor defaults.
            db.openHelper.writableDatabase.query("PRAGMA table_info(api_configs)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val defaultIdx = cursor.getColumnIndexOrThrow("dflt_value")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == "thinkingDisabled") {
                        found = true
                        assertEquals("thinkingDisabled 的 SQL 默认值应为 0", "0", cursor.getString(defaultIdx))
                    }
                }
                assertTrue("迁移后 api_configs.thinkingDisabled 列应存在", found)
            }

            // Insert a row omitting thinkingDisabled to prove SQLite itself applies the legacy-safe default.
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO api_configs (
                    id, name, apiUrl, apiKey, model, isDefault, lastTestStatus,
                    lastTestTime, sortOrder, type, context1m, streamingEnabled, createdAt, updatedAt
                ) VALUES ('migration-default-test', '迁移默认值测试', 'https://example.invalid/v1', 'test-key',
                    'test-model', 0, 'UNKNOWN', 0, 0, 'openai', 0, 1, 0, 0)
                """.trimIndent(),
            )
            db.openHelper.writableDatabase
                .query("SELECT thinkingDisabled FROM api_configs WHERE id = ?", arrayOf("migration-default-test"))
                .use { cursor ->
                    assertTrue("测试 API 配置应写入成功", cursor.moveToFirst())
                    assertEquals("省略 thinkingDisabled 时数据库默认应为 false/0", 0, cursor.getInt(0))
                }

            // The migrated Room database must still accept writes after the new columns are validated.
            db.messageDao().upsert(
                com.mistbell.tavern.android.data.local.entity.MessageEntity(
                    id = "msg-v27-write",
                    sessionId = "sess-1",
                    ownerId = "local-user",
                    characterId = "char-1",
                    role = "assistant",
                    content = "v27 写入验证",
                    thinking = null,
                    createdAt = "2026-10-14T00:00:00Z",
                    memoryIdsJson = "",
                    swipesJson = "",
                    swipeIndex = 0,
                    thinkingSwipesJson = "",
                ),
            )
            val written = db.messageDao().getBySession("sess-1", "local-user").first()
            assertTrue("迁移后的消息表应可正常写入和查询", written.any { it.id == "msg-v27-write" })
        }

}
