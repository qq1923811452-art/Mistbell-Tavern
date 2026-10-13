package com.mistbell.tavern.android.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

// ---- 会话级消息读写（数据层审查修复）----
// 会话是消息的完整归属单元；character_id 仅作说话方元数据（群聊落库时写实际发言 NPC 的 id），
// 读写一律会话级——群聊 NPC 消息与主角色消息同窗可见。因此下列查询只按
// (session_id, owner_id) 过滤，不再按 character_id 切分窗口。
// 索引核验：全部查询的过滤/排序均命中 MessageEntity 既有 (session_id, created_at) 复合索引
// （等值 session_id 后按 created_at 排序/游标扫描），无需新增索引，数据库 schema 不变。
@Dao
interface MessageDao {
    // 会话全量历史（Flow）：按 created_at ASC 返回，PromptBuilder 构建提示词与计数自愈均走此查询
    @Query(
        """
        SELECT * FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
        ORDER BY created_at ASC
        """,
    )
    fun getBySession(
        sessionId: String,
        ownerId: String,
    ): Flow<List<MessageEntity>>

    // 消息窗口分页（v16 性能修复）：仅取最新 limit 条（DESC），仓库层反转为 ASC 展示，
    // 避免长会话一次性加载全表；查询命中 (session_id, created_at) 复合索引。
    // 修复3：ORDER BY 追加 id DESC 做 tie-break——id 是 UUID 字符串、字典序稳定，
    // 导入会话保留原时间戳时同 created_at 的消息有确定次序，不会被 LIMIT 不可预测地切开。
    // PromptBuilder 构建提示词仍走上方 getBySession 全量取历史，语义不变。
    @Query(
        """
        SELECT * FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun getLatestBySession(
        sessionId: String,
        ownerId: String,
        limit: Int,
    ): Flow<List<MessageEntity>>

    // 上滚加载更旧一页（修复3：稳定复合游标）：游标为 (beforeCreatedAt, beforeId) 二元组，
    // WHERE 改为 created_at < 游标时间 OR (created_at = 游标时间 AND id < 游标 id)，
    // 与 getLatestBySession 的 (created_at DESC, id DESC) 排序构成全序——同 created_at 的
    // 并列消息不会被 LIMIT 切开永久丢失；DESC + LIMIT 由索引反向扫描取临近一页。
    @Query(
        """
        SELECT * FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
          AND (created_at < :beforeCreatedAt OR (created_at = :beforeCreatedAt AND id < :beforeId))
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getOlderBySession(
        sessionId: String,
        ownerId: String,
        beforeCreatedAt: String,
        beforeId: String,
        limit: Int,
    ): List<MessageEntity>

    // 单条读取：id + sessionId + ownerId 三重校验（会话级归属），不再区分 character_id
    @Query("SELECT * FROM messages WHERE id = :messageId AND session_id = :sessionId AND owner_id = :ownerId LIMIT 1")
    suspend fun getById(
        messageId: String,
        sessionId: String,
        ownerId: String,
    ): MessageEntity?

    @Query(
        """
        UPDATE messages
        SET prompt_context_snapshot_json = :snapshotJson
        WHERE id = :messageId AND session_id = :sessionId AND owner_id = :ownerId
        """,
    )
    suspend fun updatePromptContextSnapshot(
        messageId: String,
        sessionId: String,
        ownerId: String,
        snapshotJson: String,
    )

    @Upsert
    suspend fun upsert(msg: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND owner_id = :ownerId")
    suspend fun deleteBySession(
        sessionId: String,
        ownerId: String,
    )

    // 注意：必须先于 deleteById(target) 调用——本查询通过子查询定位目标消息的时间，
    // 若目标行已不存在则整条条件为 NULL，不会删除任何消息（安全边界，调用方依赖此语义）。
    // created_at 为 ISO-8601 字符串，字典序即时间序；同一时间戳的并列消息用 rowid（插入序）决胜，
    // 保证"目标之后"的语义在导入数据等时间重复场景下依然正确。
    @Query(
        """
        DELETE FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
          AND (
              created_at > (SELECT target.created_at FROM messages target WHERE target.id = :afterMessageId)
              OR (
                  created_at = (SELECT target.created_at FROM messages target WHERE target.id = :afterMessageId)
                  AND rowid > (SELECT target.rowid FROM messages target WHERE target.id = :afterMessageId)
              )
          )
    """,
    )
    suspend fun deleteAfter(
        sessionId: String,
        afterMessageId: String,
        ownerId: String,
    )

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    // 加 AND is_read = 0：已全部读过时 UPDATE 影响 0 行，Room 不会触发 invalidation，
    // 避免自动已读每次都让消息流整体重发一遍
    @Query(
        """
        UPDATE messages SET is_read = 1
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
          AND is_read = 0
        """,
    )
    suspend fun markAsRead(
        sessionId: String,
        ownerId: String,
    )

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
          AND is_read = 0
        """,
    )
    suspend fun getUnreadCount(
        sessionId: String,
        ownerId: String,
    ): Int

    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT session_id, MAX(created_at) as max_created_at
            FROM messages
            WHERE owner_id = :ownerId
            GROUP BY session_id
        ) latest ON m.session_id = latest.session_id AND m.created_at = latest.max_created_at
        WHERE m.owner_id = :ownerId
    """,
    )
    suspend fun getLatestMessagesByOwner(ownerId: String): List<MessageEntity>

    // F3-FTS 词法召回：取会话内最近 N 条消息的 id，作为"近期窗口"排除集——
    // 这些消息本来就在上下文里，历史召回时不应重复出现。
    @Query(
        """
        SELECT id FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
        ORDER BY created_at DESC
        LIMIT :limit
        """,
    )
    suspend fun latestIdsBySession(
        sessionId: String,
        ownerId: String,
        limit: Int,
    ): List<String>

    // F3-FTS 词法召回：按关键词（LIKE）检索历史消息，排除近期窗口内的 id。
    // 未用满的词位由调用方填充 "~~nomatch~~%"（不会匹配任何真实内容），LIKE 对 % 无需转义（模式固定）。
    // 参数即固定 6 个 LIKE 词位 + 排除集 + 结果上限的 SQL 绑定槽位，均为语义必需；
    // 压缩参数会改变召回行为或引入 @RawQuery 动态拼装，故本地豁免 detekt LongParameterList。
    @Suppress("LongParameterList")
    @Query(
        """
        SELECT * FROM messages
        WHERE session_id = :sessionId
          AND owner_id = :ownerId
          AND id NOT IN (:excludedIds)
          AND (content LIKE :t1 OR content LIKE :t2 OR content LIKE :t3
            OR content LIKE :t4 OR content LIKE :t5 OR content LIKE :t6)
        ORDER BY created_at DESC
        LIMIT :resultLimit
        """,
    )
    suspend fun searchByContentTerms(
        sessionId: String,
        ownerId: String,
        excludedIds: List<String>,
        t1: String,
        t2: String,
        t3: String,
        t4: String,
        t5: String,
        t6: String,
        resultLimit: Int,
    ): List<MessageEntity>

    /** 备份导出用：一次性全表读取（全 owner 完整快照） */
    @Query("SELECT * FROM messages")
    suspend fun getAllOnce(): List<com.mistbell.tavern.android.data.local.entity.MessageEntity>
}
