package com.mistbell.tavern.android.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.Usage
import com.mistbell.tavern.android.data.api.model.*
import com.mistbell.tavern.android.data.local.entity.*
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.service.MemoryExtractionService
import com.mistbell.tavern.android.util.parseGroupSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class ChatRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database
    private val api get() = ApiClient.getApi(context)
    private val settingsRepo = SettingsRepository(context)
    private val structuredMemoryRepo = StructuredMemoryRepository(context)
    private val memoryExtractionService = MemoryExtractionService(context, structuredMemoryRepo)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // 消息窗口默认大小（v16 性能修复）：首屏只观察最新 200 条，更旧消息由 loadOlderMessages 按页补加载
        const val DEFAULT_MESSAGE_WINDOW = 200

        // 群聊推动语（continueGroupChat）：由 ChatRepository 作为 system 消息传给 PromptBuilder
        // （classic 路径不涉及）；文案要求"让最合适的下一位角色自然接话"
        internal const val GROUP_CONTINUE_NUDGE = "（请让最合适的下一位角色自然接话，保持对话推进）"

        // 记忆抽取攒批：满 3 轮才合并发起一次 LLM 调用（抽取的输出/思考成本约占每轮总成本 80%+，
        // 批量化把每次调用的固定输入/思考开销摊到 1/3）；不足一批的尾巴由
        // flushPendingMemoryExtractions 在切会话/退出聊天页时抽掉
        private const val MEMORY_EXTRACTION_BATCH_TURNS = 3
    }

    /** 一轮待抽取对话（append 落库时入缓冲；被撤销/回退/重生覆盖的轮次会被剔除） */
    private data class PendingExtractionTurn(
        val userMessage: String,
        val assistantMessage: String,
        val messageIds: List<String>,
    )

    /** 同一 session 的待发缓冲：会话归属固定，providerId 每次入队刷新为最新值 */
    private class PendingExtractionBuffer(
        val ownerId: String,
        val characterId: String,
        var providerId: String,
        val turns: MutableList<PendingExtractionTurn> = mutableListOf(),
    )

    /** 攒满一批（或 flush 排空）后交给后台抽取的不可变快照 */
    private data class PendingExtractionBatch(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val providerId: String,
        val turns: List<PendingExtractionTurn>,
    )

    private val pendingExtractionLock = Any()
    private val pendingExtractions = mutableMapOf<String, PendingExtractionBuffer>()

    // --- Local-first reads ---

    // 域映射与 distinctUntilChanged 的按值比较都可能有开销，统一切到 Default 线程，
    // 避免阻塞 Room 回调线程或收集方（主线程）
    fun observeCharacters(): Flow<List<Character>> {
        return db.characterDao().getAll().map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    fun observeSessions(
        ownerId: String,
        characterId: String,
    ): Flow<List<SessionSummary>> {
        return db.sessionDao().getByCharacter(ownerId, characterId).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    fun observeRecentSessions(ownerId: String): Flow<List<SessionSummary>> {
        return db.sessionDao().getRecent(ownerId).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    // 消息窗口观察：只取最新 limit 条（DESC 查询反转回 ASC 展示），长会话不再全表加载。
    // Message 是 data class，distinctUntilChanged 按值去重，过滤 Room 无效化导致的多余重发。
    // 会话级语义（跨代理契约 2）：characterId 参数仅为调用方兼容保留，不再下传 DAO——
    // 会话是消息的完整归属单元，character_id 仅作说话方元数据（群聊），观察窗口按
    // (session_id, owner_id) 全量取，群聊 NPC 消息与主角色消息同窗可见。
    fun observeMessages(
        ownerId: String,
        characterId: String,
        sessionId: String,
        limit: Int = DEFAULT_MESSAGE_WINDOW,
    ): Flow<List<Message>> {
        return db.messageDao().getLatestBySession(sessionId, ownerId, limit).map { entities ->
            entities.map { it.toDomain() }.asReversed()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    // 上滚加载更旧一页：一次性挂起查询（非流）。修复3：游标为复合游标（窗口最旧一条的
    // created_at + id），与 DAO 的 (created_at DESC, id DESC) 排序构成全序，
    // 同 created_at 的并列消息不会被 LIMIT 切开永久丢失。
    // 会话级语义（跨代理契约 2）：characterId 参数仅为调用方兼容保留，不再下传 DAO。
    suspend fun loadOlderMessages(
        sessionId: String,
        ownerId: String,
        characterId: String,
        beforeCreatedAt: String,
        beforeId: String,
        limit: Int,
    ): List<Message> {
        return withContext(Dispatchers.IO) {
            db.messageDao()
                .getOlderBySession(sessionId, ownerId, beforeCreatedAt, beforeId, limit)
                .map { it.toDomain() }
                .asReversed()
        }
    }

    suspend fun getActiveSessionId(
        ownerId: String,
        characterId: String,
    ): String {
        val sessions = db.sessionDao().getByCharacter(ownerId, characterId).first()
        return sessions.firstOrNull()?.id ?: ""
    }

    // --- Write operations (local-first + optional sync) ---

    suspend fun sendMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        message: String,
        worldBookId: String = "",
        onPartial: ((String) -> Unit)? = null,
        // 群聊上下文（跨代理契约 4）：group 模式由 VM 传入（含 @提及 解析出的 targetSpeakerId）；
        // null = classic 模式（或未传时按会话 mode 兜底构建），提示词与归属行为与改动前完全一致
        groupContext: GroupChatContext? = null,
    ): Message {
        return withContext(Dispatchers.IO) {
            val msgId = UUID.randomUUID().toString()
            val userMsg =
                Message(
                    id = msgId,
                    role = "user",
                    content = message,
                    thinking = null,
                    createdAt = java.time.Instant.now().toString(),
                    memoryIds = null,
                    swipes = null,
                    swipeIndex = 0,
                )

            // 1. Save user message locally
            db.messageDao().upsert(
                MessageEntity.fromDomain(userMsg, sessionId, ownerId, characterId),
            )

            // 1.5. 向量化用户消息（异步，不阻塞主流程）
            storeUserMessageVector(
                content = message,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = msgId,
            )

            // 2. Update session message count
            val session = db.sessionDao().get(sessionId, ownerId, characterId)
            if (session != null) {
                db.sessionDao().upsert(
                    session.copy(
                        messageCount = session.messageCount + 1,
                        updatedAt = userMsg.createdAt,
                        title =
                            if (session.title.isBlank() && session.messageCount == 0) {
                                message.take(26)
                            } else {
                                session.title
                            },
                    ),
                )
            }

            // 3. Try to get AI response via LLM
            var insertedAssistantId: String? = null
            // 会话三元组收拢（owner + 主角色 + 会话）：落库/取消路径共用，避免私有 API 参数爆炸
            val scope = SessionScope(ownerId, characterId, sessionId)
            // 群聊上下文作用域覆盖 try/catch：取消路径也需据此做归属解析（群聊部分回复剥前缀）
            var effectiveGroupContext: GroupChatContext? = null
            // 流式累计缓冲，作用域覆盖整个 try，取消时据此判断是否已有部分回复
            val sb = StringBuilder()
            try {
                val llmConfig = loadLlmConfig(sessionId)
                if (llmConfig.baseUrl.isNotBlank() && llmConfig.apiKey.isNotBlank()) {
                    // 群聊模式：优先用 VM 传入的 groupContext；未传时按会话 mode/参与者兜底构建
                    effectiveGroupContext = groupContext ?: loadGroupContext(ownerId, characterId, sessionId)
                    // 参与者空名单兜底（minor 修复）：VM 首帧竞态可能传入 speakerNames 为空 map 的
                    // groupContext（参与者尚未加载完成），此时按会话重建一次，防止整轮群聊退化为
                    // 无说话方表（历史无前缀、回复无法归属）
                    if (effectiveGroupContext?.speakerNames?.isEmpty() == true) {
                        effectiveGroupContext =
                            loadGroupContext(ownerId, characterId, sessionId) ?: effectiveGroupContext
                    }
                    val promptMessages =
                        PromptBuilder.buildPrompt(
                            db,
                            ownerId,
                            characterId,
                            sessionId,
                            message,
                            currentMessageId = msgId,
                            groupContext = effectiveGroupContext,
                        )
                    val usage = collectReply(llmConfig, promptMessages, sb, onPartial)
                    // 公共尾部：群聊归属解析 + 落库 + 向量化 + 计数回写 + 记忆提取
                    // （classic 时 speakerNames 为 null，归属/内容与改动前逐字节一致）
                    val assistantMsg =
                        persistAssistantReply(
                            fullReply = sb.toString(),
                            scope = scope,
                            speakerNames = effectiveGroupContext?.speakerNames,
                            mode = ReplyTailMode.Append(userMessageForMemory = message, userMessageId = msgId),
                            usage = usage,
                        )
                    insertedAssistantId = assistantMsg.id

                    return@withContext assistantMsg
                } else {
                    throw Exception("LLM 未配置：请在设置中配置 API 密钥")
                }
            } catch (e: CancellationException) {
                // 用户主动停止生成：不同于网络失败，不回滚用户消息。
                // 已收到部分回复则落库并回写计数，然后向上抛出取消。
                // 取消路径统一（跨代理契约 3）：群聊先做与成功路径 persistAssistantReply 相同的
                // splitThinking + parseGroupSpeaker 归属解析再落库；classic 行为不变（原文落库）。
                // effectiveGroupContext 尚未构建时（取消发生在提示词组装前）按会话兜底重建
                if (sb.isNotEmpty()) {
                    val cancelSpeakerNames =
                        effectiveGroupContext?.speakerNames
                            ?: loadGroupContext(ownerId, characterId, sessionId)?.speakerNames
                    persistPartialReplyOnCancel(
                        partialReply = sb.toString(),
                        scope = scope,
                        speakerNames = cancelSpeakerNames,
                    )
                }
                throw e
            } catch (e: Exception) {
                // LLM 调用或收尾失败：事务内回滚本条消息（用户消息，若已插入还包括回复），
                // 计数按真实行数重算（自愈，不依赖增量加减），再抛出异常让 UI 显示错误。
                // 已知残留：已异步写入的向量无法按消息清理（无对应接口，见 ROADMAP 向量双写一致性）
                db.withTransaction {
                    db.messageDao().deleteById(msgId)
                    insertedAssistantId?.let { db.messageDao().deleteById(it) }
                    val sessionForRollback = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (sessionForRollback != null) {
                        db.sessionDao().upsert(
                            sessionForRollback.copy(
                                messageCount = db.messageDao().getBySession(sessionId, ownerId).first().size,
                                title = if (sessionForRollback.title == message.take(26)) "" else sessionForRollback.title,
                            ),
                        )
                    }
                }
                deleteMessageVectors(msgId)
                insertedAssistantId?.let { deleteMessageVectors(it) }
                throw e
            }
        }
    }

    /**
     * 群聊推动（跨代理契约 4）：不插入用户消息，注入系统级推动语让最合适的下一位角色接话。
     * 回复归属解析/落库/向量化/会话计数回写与 sendMessage 共用 persistAssistantReply 公共尾部。
     *
     * @param worldBookId 预留参数（与 sendMessage 一致，实际世界书由 PromptBuilder 按会话/角色解析）
     */
    suspend fun continueGroupChat(
        ownerId: String,
        characterId: String,
        sessionId: String,
        worldBookId: String = "",
        onPartial: ((String) -> Unit)? = null,
    ): Message {
        return withContext(Dispatchers.IO) {
            // 仅群聊会话可推动；classic 会话直接报错（UI 由 groupMode 门控，此处双保险）
            val groupContext =
                loadGroupContext(ownerId, characterId, sessionId)
                    ?: throw IllegalStateException("会话不存在或不是群聊模式，无法推动群聊")
            var insertedAssistantId: String? = null
            val scope = SessionScope(ownerId, characterId, sessionId)
            val sb = StringBuilder()
            try {
                val llmConfig = loadLlmConfig(sessionId)
                if (llmConfig.baseUrl.isBlank() || llmConfig.apiKey.isBlank()) {
                    throw Exception("LLM 未配置：请在设置中配置 API 密钥")
                }
                val promptMessages =
                    PromptBuilder.buildPrompt(
                        db,
                        ownerId,
                        characterId,
                        sessionId,
                        // 无新用户消息；推动语作为 system 注入（groupNudge 非 null 时 PromptBuilder 不追加用户消息）
                        userMessage = "",
                        groupContext = groupContext,
                        groupNudge = GROUP_CONTINUE_NUDGE,
                    )
                val usage = collectReply(llmConfig, promptMessages, sb, onPartial)
                // 公共尾部：归属解析/落库/计数回写与 sendMessage 完全一致；本轮无用户消息参与记忆提取
                val assistantMsg =
                    persistAssistantReply(
                        fullReply = sb.toString(),
                        scope = scope,
                        speakerNames = groupContext.speakerNames,
                        mode = ReplyTailMode.Append(userMessageForMemory = "", userMessageId = null),
                        usage = usage,
                    )
                insertedAssistantId = assistantMsg.id

                return@withContext assistantMsg
            } catch (e: CancellationException) {
                // 用户主动停止生成：已收到部分回复则落库并回写计数。
                // 取消路径统一（跨代理契约 3）：群聊部分回复先做与 persistAssistantReply 一致的
                // splitThinking + parseGroupSpeaker 归属解析再落库（复用 persistPartialReplyOnCancel）
                if (sb.isNotEmpty()) {
                    persistPartialReplyOnCancel(
                        partialReply = sb.toString(),
                        scope = scope,
                        speakerNames = groupContext.speakerNames,
                    )
                }
                throw e
            } catch (e: Exception) {
                // LLM 调用或收尾失败：无用户消息可回滚，仅删除已插入的助手回复并按真实行数重算计数
                db.withTransaction {
                    insertedAssistantId?.let { db.messageDao().deleteById(it) }
                    val sessionForRollback = db.sessionDao().get(sessionId, ownerId, characterId)
                    if (sessionForRollback != null) {
                        db.sessionDao().upsert(
                            sessionForRollback.copy(
                                messageCount = db.messageDao().getBySession(sessionId, ownerId).first().size,
                            ),
                        )
                    }
                }
                throw e
            }
        }
    }

    suspend fun undoLastMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 事务保证删除与计数回写原子完成，避免中途失败导致计数漂移
            val deletedMessageId =
                db.withTransaction {
                    val messages = db.messageDao().getBySession(sessionId, ownerId).first()
                    if (messages.isNotEmpty()) {
                        db.messageDao().deleteById(messages.last().id)
                        val session = db.sessionDao().get(sessionId, ownerId, characterId)
                        if (session != null) {
                            db.sessionDao().upsert(session.copy(messageCount = messages.size - 1))
                        }
                    }
                    messages.lastOrNull()?.id
                }
            deletedMessageId?.let { deleteMessageVectors(it) }
            val remainingIds = db.messageDao().getBySession(sessionId, ownerId).first().mapTo(HashSet()) { it.id }
            prunePendingExtractionTurns(sessionId, remainingIds)
        }
    }

    suspend fun backtrackToMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
    ) {
        withContext(Dispatchers.IO) {
            val messages = db.messageDao().getBySession(sessionId, ownerId).first()
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                db.messageDao().deleteAfter(sessionId, messageId, ownerId)
                messages.drop(idx + 1).forEach { deleteMessageVectors(it.id) }
                prunePendingExtractionTurns(sessionId, messages.take(idx + 1).mapTo(HashSet()) { it.id })
            }
        }
    }

    suspend fun regenerateMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        onPartial: ((String) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val msg = db.messageDao().getById(messageId, sessionId, ownerId) ?: return@withContext
            if (msg.role != "assistant") return@withContext
            val scope = SessionScope(ownerId, characterId, sessionId)

            // 先取上下文与配置，任何删除都在拿到新回复成功之后，避免旧消息丢失而新回复没来
            // （会话级读取：群聊 NPC 消息与主角色消息同窗，均在可追溯的用户消息范围内）
            val userMessages = db.messageDao().getBySession(sessionId, ownerId).first()
            val lastUserMsg =
                userMessages.lastOrNull { it.role == "user" }
                    ?: throw IllegalStateException("没有可重新生成的用户消息")
            val llmConfig = loadLlmConfig(sessionId)
            if (llmConfig.baseUrl.isBlank() || llmConfig.apiKey.isBlank()) {
                throw IllegalStateException("LLM 未配置：请在设置中配置 API 密钥")
            }

            // 群聊对齐（跨代理契约 4）：group 会话按 loadGroupContext 构建群聊上下文传 buildPrompt，
            // 提示词行为（规范块、历史说话方前缀、当前消息前缀）与 sendMessage/continueGroupChat 一致；
            // classic 会话 groupContext 为 null，提示词与改动前完全一致
            val session = db.sessionDao().get(sessionId, ownerId, characterId)
            val groupContext =
                if (session?.mode == SESSION_MODE_GROUP) {
                    loadGroupContext(ownerId, characterId, sessionId)
                } else {
                    null
                }

            // excludeFromMessageId：截断目标消息及其之后的历史，
            // 保证正要被替换的旧回复不进入上下文（否则模型会复述旧答案）
            val prompt =
                PromptBuilder.buildPrompt(
                    db,
                    ownerId,
                    characterId,
                    sessionId,
                    lastUserMsg.content,
                    currentMessageId = lastUserMsg.id,
                    excludeFromMessageId = messageId,
                    groupContext = groupContext,
                )
            val sb = StringBuilder()
            var usage: Usage? = null
            val replacedMessageIds =
                userMessages
                    .dropWhile { it.id != messageId }
                    .map { it.id }
            try {
                usage = collectReply(llmConfig, prompt, sb, onPartial)
            } catch (e: CancellationException) {
                // 用户主动停止重新生成：不触发失败回滚。
                // 已收到部分回复则按替换事务落库（群聊先做与成功路径一致的归属解析，
                // 复用 persistPartialReplyOnCancel，勿复制粘贴）；空则直接上抛取消。
                if (sb.isNotEmpty()) {
                    persistPartialReplyOnCancel(
                        partialReply = sb.toString(),
                        scope = scope,
                        speakerNames = groupContext?.speakerNames,
                        replaceFromMessageId = messageId,
                    )
                }
                throw e
            }

            // 成功拿到新回复：落库复用 persistAssistantReply 公共尾部（跨代理契约 4）——
            // 归属解析 + 替换事务（删目标及其后、落新消息、按真实行数回写计数）+ 向量化。
            // 已知残留：被替换的旧消息向量没有按消息删除的接口，暂无法清理（见 ROADMAP 向量双写一致性问题）
            persistAssistantReply(
                fullReply = sb.toString(),
                scope = scope,
                speakerNames = groupContext?.speakerNames,
                mode = ReplyTailMode.Replace(fromMessageId = messageId),
                usage = usage,
            )
            replacedMessageIds.forEach { deleteMessageVectors(it) }
        }
    }

    suspend fun continueMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        onPartial: ((String) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val messages = db.messageDao().getBySession(sessionId, ownerId).first()
            val target = messages.lastOrNull { it.role == "assistant" }
                ?: throw IllegalStateException("没有可继续的助手消息")
            val lastUser = messages.lastOrNull { it.role == "user" && it.createdAt < target.createdAt }
                ?: throw IllegalStateException("没有可继续的用户消息")
            val config = loadLlmConfig(sessionId)
            if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
                throw IllegalStateException("LLM 未配置：请在设置中配置 API 密钥")
            }
            val prompt =
                PromptBuilder.buildPrompt(
                    db,
                    ownerId,
                    characterId,
                    sessionId,
                    userMessage = "请自然地继续上一条助手回复，不要重复已经说过的内容。",
                    groupContext = loadGroupContext(ownerId, characterId, sessionId),
                )
            val sb = StringBuilder()
            var usage: Usage? = null
            try {
                usage = collectReply(config, prompt, sb, onPartial)
            } catch (e: CancellationException) {
                if (sb.isNotEmpty()) appendContinuation(target, sb.toString(), ownerId, characterId, sessionId)
                throw e
            }
            appendContinuation(target, sb.toString(), ownerId, characterId, sessionId)
        }
    }

    suspend fun swipeMessage(
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageId: String,
        direction: String,
        onPartial: ((String) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val entity = db.messageDao().getById(messageId, sessionId, ownerId)
                ?: throw IllegalArgumentException("消息不存在")
            if (entity.role != "assistant") throw IllegalArgumentException("只有助手消息支持 swipe")
            val existing = decodeStringList(entity.swipesJson).ifEmpty { listOf(entity.content) }.toMutableList()
            val thinkingSwipes = decodeStringList(entity.thinkingSwipesJson).toMutableList()
            val currentIndex = entity.swipeIndex.coerceIn(0, existing.lastIndex)
            // Keep imported/legacy swipe arrays index-aligned with their thinking text.
            while (thinkingSwipes.size < existing.size) {
                val index = thinkingSwipes.size
                thinkingSwipes += if (index == currentIndex) entity.thinking.orEmpty() else ""
            }
            if (thinkingSwipes.size > existing.size) {
                thinkingSwipes.subList(existing.size, thinkingSwipes.size).clear()
            }
            val step = if (direction.equals("left", ignoreCase = true)) -1 else 1
            val nextIndex = currentIndex + step
            if (nextIndex in existing.indices) {
                db.messageDao().upsert(
                    entity.copy(
                        content = existing[nextIndex],
                        thinking = thinkingSwipes.getOrNull(nextIndex).orEmpty().ifBlank { null },
                        swipeIndex = nextIndex,
                        swipesJson = encodeStringList(existing),
                        thinkingSwipesJson = encodeStringList(thinkingSwipes),
                    ),
                )
                deleteMessageVectors(messageId)
                storeAssistantMessageVector(existing[nextIndex], ownerId, entity.characterId.ifBlank { characterId }, sessionId, messageId)
                return@withContext
            }
            val lastUser = db.messageDao().getBySession(sessionId, ownerId).first()
                .lastOrNull { it.role == "user" && it.createdAt < entity.createdAt }
                ?: throw IllegalStateException("没有可用于生成替代回复的用户消息")
            val config = loadLlmConfig(sessionId)
            if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
                throw IllegalStateException("LLM 未配置：请在设置中配置 API 密钥")
            }
            val prompt =
                PromptBuilder.buildPrompt(
                    db,
                    ownerId,
                    characterId,
                    sessionId,
                    lastUser.content,
                    currentMessageId = lastUser.id,
                    excludeFromMessageId = messageId,
                    groupContext = loadGroupContext(ownerId, characterId, sessionId),
                )
            val sb = StringBuilder()
            var usage: Usage? = null
            try {
                usage = collectReply(config, prompt, sb, onPartial)
            } catch (e: CancellationException) {
                // A stopped swipe still owns a valid partial candidate. Persist it
                // before propagating cancellation so Room and the UI stay aligned.
                if (sb.isNotEmpty()) {
                    val (partialContent, partialThinking, partialCharacterId) =
                        resolveReplyAttribution(
                            sb.toString(),
                            loadGroupContext(ownerId, characterId, sessionId)?.speakerNames,
                            characterId,
                        )
                    if (partialContent.isNotBlank() || !partialThinking.isNullOrBlank()) {
                        persistSwipeCandidate(
                            entity,
                            existing,
                            thinkingSwipes,
                            step,
                            partialContent,
                            partialThinking,
                            partialCharacterId,
                            ownerId,
                            characterId,
                            sessionId,
                        )
                    }
                }
                throw e
            }
            val (newContent, newThinking, storedCharacterId) =
                resolveReplyAttribution(sb.toString(), loadGroupContext(ownerId, characterId, sessionId)?.speakerNames, characterId)
            if (newContent.isBlank() && newThinking.isNullOrBlank()) return@withContext
            persistSwipeCandidate(
                entity,
                existing,
                thinkingSwipes,
                step,
                newContent,
                newThinking,
                storedCharacterId,
                ownerId,
                characterId,
                sessionId,
                usage = usage,
            )
        }
    }

    private suspend fun persistSwipeCandidate(
        entity: MessageEntity,
        existing: MutableList<String>,
        thinkingSwipes: MutableList<String>,
        step: Int,
        content: String,
        thinking: String?,
        storedCharacterId: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        usage: Usage? = null,
    ) {
        if (step > 0) {
            existing += content
            thinkingSwipes += thinking.orEmpty()
        } else {
            existing.add(0, content)
            thinkingSwipes.add(0, thinking.orEmpty())
        }
        while (thinkingSwipes.size < existing.size) thinkingSwipes += ""
        if (thinkingSwipes.size > existing.size) {
            thinkingSwipes.subList(existing.size, thinkingSwipes.size).clear()
        }
        val newIndex = if (step > 0) existing.lastIndex else 0
        db.messageDao().upsert(
            entity.copy(
                characterId = storedCharacterId,
                content = content,
                thinking = thinking,
                swipesJson = encodeStringList(existing),
                swipeIndex = newIndex,
                thinkingSwipesJson = encodeStringList(thinkingSwipes),
                usageJson = encodeUsage(usage),
            ),
        )
        deleteMessageVectors(entity.id)
        storeAssistantMessageVector(content, ownerId, storedCharacterId.ifBlank { characterId }, sessionId, entity.id)
    }

    suspend fun clearConversation(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 会话级删除：群聊 NPC 消息与主角色消息同属一个归属单元，一并清空
            db.messageDao().deleteBySession(sessionId, ownerId)
            deleteSessionVectors(ownerId, characterId, sessionId)
        }
    }

    private suspend fun appendContinuation(
        target: MessageEntity,
        rawReply: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        val (continuation, continuationThinking, storedCharacterId) =
            resolveReplyAttribution(rawReply, loadGroupContext(ownerId, characterId, sessionId)?.speakerNames, characterId)
        if (continuation.isBlank() && continuationThinking.isNullOrBlank()) return
        val mergedContent = listOf(target.content.trimEnd(), continuation.trimStart()).filter { it.isNotBlank() }.joinToString("\n")
        val mergedThinking = listOfNotNull(target.thinking?.takeIf { it.isNotBlank() }, continuationThinking).joinToString("\n\n").ifBlank { null }
        val swipes = decodeStringList(target.swipesJson).toMutableList()
        val thinkingSwipes = decodeStringList(target.thinkingSwipesJson).toMutableList()
        if (swipes.isNotEmpty()) {
            val currentIndex = target.swipeIndex.coerceIn(0, swipes.lastIndex)
            while (thinkingSwipes.size < swipes.size) thinkingSwipes += ""
            if (thinkingSwipes.size > swipes.size) thinkingSwipes.subList(swipes.size, thinkingSwipes.size).clear()
            swipes[currentIndex] = mergedContent
            thinkingSwipes[currentIndex] = mergedThinking.orEmpty()
        }
        db.messageDao().upsert(
            target.copy(
                characterId = storedCharacterId,
                content = mergedContent,
                thinking = mergedThinking,
                swipesJson = if (swipes.isEmpty()) target.swipesJson else encodeStringList(swipes),
                thinkingSwipesJson = if (swipes.isEmpty()) target.thinkingSwipesJson else encodeStringList(thinkingSwipes),
            ),
        )
        deleteMessageVectors(target.id)
        storeAssistantMessageVector(mergedContent, ownerId, storedCharacterId, sessionId, target.id)
        db.sessionDao().get(sessionId, ownerId, characterId)?.let { session ->
            db.sessionDao().upsert(session.copy(updatedAt = java.time.Instant.now().toString()))
        }
    }

    private fun decodeStringList(jsonText: String): List<String> =
        try {
            if (jsonText.isBlank()) emptyList() else Json.decodeFromString<List<String>>(jsonText)
        } catch (_: Exception) {
            emptyList()
        }

    private fun encodeStringList(values: List<String>): String =
        Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
            values,
        )

    private fun encodeUsage(usage: Usage?): String? = usage?.let { Json.encodeToString(Usage.serializer(), it) }

    // null = 跟随全局默认（读取时解析）；显式值才会压过全局——建会话不再快照全局默认
    suspend fun createSession(
        ownerId: String,
        characterId: String,
        title: String = "",
        providerId: String = "",
        enableLongTermMemory: Boolean? = null,
        worldBookId: String = "",
    ): String {
        return withContext(Dispatchers.IO) {
            val sessionId = UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            // 如果没有指定 providerId，取 api_configs 默认配置（会话 providerId 存 ApiConfig.id）
            val actualProviderId =
                if (providerId.isBlank()) {
                    db.apiConfigDao().getDefault()?.id ?: ""
                } else {
                    providerId
                }

            val session =
                SessionEntity(
                    id = sessionId,
                    ownerId = ownerId,
                    characterId = characterId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    messageCount = 0,
                    providerId = actualProviderId,
                    modelId = "",
                    worldBookId = worldBookId,
                    summaryJson = "",
                    enableLongTermMemory = enableLongTermMemory,
                    participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(listOf(characterId)),
                )
            db.sessionDao().upsert(session)
            sessionId
        }
    }

    suspend fun deleteSession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            // 删除该会话的所有记忆
            db.memoryDao().deleteBySession(ownerId, characterId, sessionId)
            structuredMemoryRepo.deleteMemoriesBySession(ownerId, sessionId)
            db.vectorMemoryDao().deleteBySession(ownerId, sessionId)
            deleteSessionVectors(ownerId, characterId, sessionId)
            android.util.Log.d("ChatRepository", "Deleted memories for session: $sessionId")

            // 删除消息和会话（会话级删除，含群聊 NPC 消息）
            db.messageDao().deleteBySession(sessionId, ownerId)
            db.sessionDao().delete(sessionId, ownerId, characterId)
        }
    }

    // --- Helpers ---

    // 收集完整回复：流式开 → SSE 逐增量累计，onPartial 每次回调累计全文供 UI 渲染；流式关 → 整包返回不调 onPartial。
    // sb 由调用方持有（作用域覆盖其 try/catch），取消/异常路径可据此判断并保存部分回复。
    private suspend fun collectReply(
        llmConfig: LlmConfig,
        promptMessages: List<ChatMessage>,
        sb: StringBuilder,
        onPartial: ((String) -> Unit)?,
    ): Usage? {
        if (llmConfig.streamingEnabled) {
            val thinking = StringBuilder()
            val content = StringBuilder()
            var usage: Usage? = null
            LlmClient.chatStreamWithThinking(llmConfig, promptMessages).collect { delta ->
                if (!delta.thinking.isNullOrBlank()) thinking.append(delta.thinking)
                if (delta.text.isNotEmpty()) content.append(delta.text)
                delta.usage?.let { usage = it }
                sb.replace(0, sb.length, formatReply(content.toString(), thinking.toString()))
                onPartial?.invoke(sb.toString())
            }
            return usage
        } else {
            val response = LlmClient.chatWithThinking(llmConfig, promptMessages)
            sb.append(formatReply(response.text, response.thinking))
            return response.usage
        }
    }

    /** 将协议层分离出的思维链重新编码为现有消息存储格式。 */
    private fun formatReply(
        content: String,
        thinking: String?,
    ): String {
        val cleanThinking = thinking?.trim().orEmpty()
        return if (cleanThinking.isBlank()) {
            content
        } else {
            "<think>\n$cleanThinking\n</think>\n$content"
        }
    }

    /**
     * 回复归属解析公共函数（成功/取消路径共用，避免复制粘贴）：
     * 1) F2.1 回复清洗——提取全部 <think>…</think> 块为思考内容，正文不含 think；
     * 2) 群聊归属解析——对清洗后的回复 parseGroupSpeaker：
     *    命中「名字:」前缀 → 归属说话 NPC（character_id 写为说话者 id）、内容剥前缀；
     *    未命中 → 保持主角色与清洗后原文。
     * 返回 Triple(存储正文, 思考内容, 存储归属角色 id)。
     * classic（speakerNames 为 null）时正文清洗照常、归属保持主角色。
     */
    private fun resolveReplyAttribution(
        fullReply: String,
        speakerNames: Map<String, String>?,
        fallbackCharacterId: String,
    ): Triple<String, String?, String> {
        val (replyContent, replyThinking) = splitThinking(fullReply)
        // 群聊归属 MVP：流式期间气泡按主角色显示原文，完成后才按「名字:」前缀解析真实说话者
        val speaker = speakerNames?.let { parseGroupSpeaker(replyContent, it) }
        return Triple(
            speaker?.strippedContent ?: replyContent,
            replyThinking,
            speaker?.speakerId ?: fallbackCharacterId,
        )
    }

    /**
     * 会话三元组（owner + 主角色 + 会话 id）：消息归属、会话计数回写与记忆提取的公共键。
     * 收拢为单一参数，避免仓库私有落库 API 的参数爆炸（detekt LongParameterList）。
     */
    private data class SessionScope(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
    )

    /**
     * 回复落库尾部模式：
     * - [Append]（sendMessage / continueGroupChat）：追加语义，携带记忆提取输入
     *   （群聊推动轮无用户消息，userMessageForMemory 传 ""），计数 +1；
     * - [Replace]（regenerateMessage，跨代理契约 4）：替换语义——事务内先删目标及其后的
     *   全部消息再落库，计数按真实行数自愈重算，不做记忆提取（无新用户消息参与）。
     */
    private sealed interface ReplyTailMode {
        data class Append(
            val userMessageForMemory: String,
            val userMessageId: String?,
        ) : ReplyTailMode

        data class Replace(
            val fromMessageId: String,
        ) : ReplyTailMode
    }

    /**
     * 取消路径（sendMessage / continueGroupChat / regenerateMessage）共用的部分回复落库：
     * - 群聊（speakerNames 非 null）：先做与成功路径 persistAssistantReply 一致的
     *   splitThinking + parseGroupSpeaker 归属解析再落库（跨代理契约 3）；
     * - classic（speakerNames 为 null）：保持改动前行为——原文落库、thinking 为空、归属主角色。
     * - 追加场景（replaceFromMessageId 为 null）：直接 upsert 并回写计数 +1；
     * - 替换场景（regenerateMessage）：先删目标及其后的全部消息再落库，计数按真实行数重算，
     *   同一事务内原子完成。
     */
    private suspend fun persistPartialReplyOnCancel(
        partialReply: String,
        scope: SessionScope,
        speakerNames: Map<String, String>?,
        replaceFromMessageId: String? = null,
    ) {
        val content: String
        val thinking: String?
        val storedCharacterId: String
        if (speakerNames != null) {
            val attribution = resolveReplyAttribution(partialReply, speakerNames, scope.characterId)
            content = attribution.first
            thinking = attribution.second
            storedCharacterId = attribution.third
        } else {
            // 协议层的 reasoning 在收集阶段编码成 <think> 块；取消生成也要清洗并落入独立字段，
            // 避免半截思维链出现在正文气泡中。
            val cleaned = splitThinking(partialReply)
            content = cleaned.first
            thinking = cleaned.second
            storedCharacterId = scope.characterId
        }

        val partialMsg =
            Message(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = content,
                thinking = thinking,
                createdAt = java.time.Instant.now().toString(),
                memoryIds = null,
                swipes = null,
                swipeIndex = 0,
            )

        if (replaceFromMessageId != null) {
            // 替换场景：删除旧消息与落库部分回复在同一事务内原子完成，计数按真实行数自愈重算
            db.withTransaction {
                db.messageDao().deleteAfter(scope.sessionId, replaceFromMessageId, scope.ownerId)
                db.messageDao().deleteById(replaceFromMessageId)
                db.messageDao().upsert(
                    MessageEntity.fromDomain(partialMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                )
                val session = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
                if (session != null) {
                    db.sessionDao().upsert(
                        session.copy(
                            messageCount =
                                db.messageDao().getBySession(scope.sessionId, scope.ownerId).first().size,
                        ),
                    )
                }
            }
            deleteMessageVectors(replaceFromMessageId)
        } else {
            db.messageDao().upsert(
                MessageEntity.fromDomain(partialMsg, scope.sessionId, scope.ownerId, storedCharacterId),
            )
            val sessionAfterPartial = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
            if (sessionAfterPartial != null) {
                db.sessionDao().upsert(
                    sessionAfterPartial.copy(
                        messageCount = sessionAfterPartial.messageCount + 1,
                        updatedAt = partialMsg.createdAt,
                    ),
                )
            }
        }
        storeAssistantMessageVector(
            content = content,
            ownerId = scope.ownerId,
            characterId = storedCharacterId,
            sessionId = scope.sessionId,
            messageId = partialMsg.id,
        )
    }

    /**
     * sendMessage / continueGroupChat / regenerateMessage 共用的回复落库公共尾部（避免复制粘贴）：
     * 1) 回复清洗（think 块提取）；2) 群聊归属解析（resolveReplyAttribution）；3) 助手消息落库；
     * 4) 向量化；5) 会话计数回写；6) 记忆提取（Append 入缓冲攒批，满批合并抽取；
     * Replace 剔除被覆盖轮次，群聊 MVP 仍归属主角色）。
     *
     * 群聊归属（speakerNames 非 null 时）：对清洗后的回复 parseGroupSpeaker——
     * 命中「名字:」前缀 → character_id 写为说话 NPC id、内容剥前缀；未命中 → 保持主角色与原文。
     * classic（speakerNames 为 null）时归属/内容与改动前完全一致。
     *
     * mode：Append 走完整尾部（计数 +1 + 记忆提取）；Replace（regenerateMessage）在同一事务内
     * 原子完成「删目标及其后的全部消息 → 落新回复 → 按真实行数回写计数」，随后仅向量化。
     */
    private suspend fun persistAssistantReply(
        fullReply: String,
        scope: SessionScope,
        speakerNames: Map<String, String>?,
        mode: ReplyTailMode,
        usage: Usage? = null,
    ): Message {
        // 回复清洗 + 群聊归属解析（与取消路径共用 resolveReplyAttribution）
        val (storedContent, replyThinking, storedCharacterId) =
            resolveReplyAttribution(fullReply, speakerNames, scope.characterId)

        val assistantMsg =
            Message(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = storedContent,
                thinking = replyThinking,
                createdAt = java.time.Instant.now().toString(),
                memoryIds = null,
                swipes = null,
                swipeIndex = 0,
                usage = usage,
            )

        when (mode) {
            is ReplyTailMode.Replace -> {
                val replacedIds =
                    db.messageDao().getBySession(scope.sessionId, scope.ownerId).first()
                        .dropWhile { it.id != mode.fromMessageId }
                        .map { it.id }
                // 替换场景：删除旧消息、落库新回复、按真实行数回写计数在同一事务内原子完成
                // （自愈式计数，不依赖增量加减）；失败回滚语义由调用方保证——删除只发生在新回复已到手之后
                db.withTransaction {
                    db.messageDao().deleteAfter(scope.sessionId, mode.fromMessageId, scope.ownerId)
                    db.messageDao().deleteById(mode.fromMessageId)
                    db.messageDao().upsert(
                        MessageEntity.fromDomain(assistantMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                    )
                    val session = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                messageCount =
                                    db.messageDao().getBySession(scope.sessionId, scope.ownerId).first().size,
                            ),
                        )
                    }
                }
                replacedIds.forEach { deleteMessageVectors(it) }
                // 重生覆盖：凡引用被删消息的待发轮次整轮剔除（新回复与改动前一致，不进缓冲）
                val remainingIds =
                    db.messageDao().getBySession(scope.sessionId, scope.ownerId).first()
                        .mapTo(HashSet()) { it.id }
                prunePendingExtractionTurns(scope.sessionId, remainingIds)
            }
            is ReplyTailMode.Append -> {
                db.messageDao().upsert(
                    MessageEntity.fromDomain(assistantMsg, scope.sessionId, scope.ownerId, storedCharacterId),
                )
            }
        }

        // 3.5. 向量化 AI 回复（异步，不阻塞主流程）——群聊归属实际说话角色
        storeAssistantMessageVector(
            content = storedContent,
            ownerId = scope.ownerId,
            characterId = storedCharacterId,
            sessionId = scope.sessionId,
            messageId = assistantMsg.id,
        )

        val updatedSession = db.sessionDao().get(scope.sessionId, scope.ownerId, scope.characterId)
        if (updatedSession != null) {
            when (mode) {
                is ReplyTailMode.Append -> {
                    // 追加场景：计数 +1 并触发记忆提取
                    val sessionAfterReply =
                        updatedSession.copy(
                            messageCount = updatedSession.messageCount + 1,
                            updatedAt = assistantMsg.createdAt,
                        )
                    db.sessionDao().upsert(sessionAfterReply)

                    // 记忆抽取攒批：本轮入缓冲，满 MEMORY_EXTRACTION_BATCH_TURNS 轮合并为一次调用
                    // （开场白已经在创建会话时插入，不参与这里的逻辑）。
                    // 群聊 MVP：记忆提取仍归属主角色 characterId（按说话者 witness 分账是后续增强）
                    enqueueMemoryExtraction(
                        // 三态覆盖：会话显式值 > 全局默认（与 PromptBuilder 的注入判断同一解析器，防双源真相）
                        enabled =
                            ChatSettingsResolver.longTermMemoryEnabled(
                                sessionAfterReply,
                                settingsRepo.defaultLtmEnabled(),
                            ),
                        providerId = sessionAfterReply.providerId,
                        userMessage = mode.userMessageForMemory,
                        assistantMessage = storedContent,
                        ownerId = scope.ownerId,
                        characterId = scope.characterId,
                        sessionId = scope.sessionId,
                        messageIds = listOfNotNull(mode.userMessageId, assistantMsg.id),
                    )
                }
                is ReplyTailMode.Replace -> {
                    // 替换场景：计数已在替换事务内按真实行数回写，仅刷新 updatedAt
                    db.sessionDao().upsert(updatedSession.copy(updatedAt = assistantMsg.createdAt))
                }
            }
        }

        return assistantMsg
    }

    // 群聊上下文兜底构建：VM 已传 groupContext 时直接使用；未传时按会话 mode + 参与者列表查角色名。
    // 非群聊会话（mode != group 或会话不存在）返回 null，classic 路径零影响。
    private suspend fun loadGroupContext(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): GroupChatContext? {
        val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return null
        if (session.mode != SESSION_MODE_GROUP) return null
        val speakerNames =
            session
                .participantCharacterIds()
                .mapNotNull { id -> db.characterDao().getById(id)?.let { id to it.name } }
                .toMap()
        return GroupChatContext(speakerNames = speakerNames)
    }

    // F2.1 回复清洗：提取全部 <think>…</think> 块，多段以空行合并为思考内容；
    // 正文为去除全部 think 块后 trim 的结果。无有效思考时 thinking 为 null。
    private fun splitThinking(reply: String): Pair<String, String?> {
        val thinking =
            Regex("(?s)<think>([\\s\\S]*?)</think>").findAll(reply)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        val content = reply.replace(Regex("(?s)<think>[\\s\\S]*?</think>"), "").trim()
        return content to thinking.ifBlank { null }
    }

    // 统一走 SettingsRepository.getLlmConfig：此前此处另读一套旧键，导致 llm_* 覆盖、
    // 采样预设、超时与重试设置对聊天请求不生效（双源真相 bug）
    // sessionId 非空时应用会话级覆盖：会话在聊天设置里指定的 API 配置
    // （providerId → api_configs；modelId 压过配置默认模型）优先于全局默认配置
    private suspend fun loadLlmConfig(sessionId: String? = null): LlmConfig {
        val base = settingsRepo.getLlmConfig()
        val session = sessionId?.let { db.sessionDao().getById(it) }
        val apiConfig =
            session?.providerId
                ?.takeIf { it.isNotBlank() }
                ?.let { db.apiConfigDao().getById(it) }
        return if (apiConfig != null) {
            base.copy(
                baseUrl = apiConfig.apiUrl,
                apiKey = apiConfig.apiKey,
                model = session.modelId.ifBlank { apiConfig.model },
                type = apiConfig.type,
                streamingEnabled = apiConfig.streamingEnabled,
                disableThinking = apiConfig.thinkingDisabled,
            )
        } else {
            base
        }
    }

    /**
     * 会话覆盖后的生效配置（internal：ChatViewModel 的"请求参数"诊断展示需与真实请求同源，
     * 不经此入口拿配置会显示全局默认值，与会话实际使用的模型不一致）
     */
    internal suspend fun effectiveLlmConfig(sessionId: String?): LlmConfig = loadLlmConfig(sessionId)

    /**
     * 一轮回复入抽取缓冲：攒满 MEMORY_EXTRACTION_BATCH_TURNS 轮后排空并合并发起一次后台抽取。
     * 不满一批的尾巴靠 flushPendingMemoryExtractions 兜底（切会话/退出聊天页时调用）。
     */
    private fun enqueueMemoryExtraction(
        enabled: Boolean,
        providerId: String,
        userMessage: String,
        assistantMessage: String,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
    ) {
        if (!enabled) {
            // 长期记忆被关掉：丢弃该会话的待发缓冲——已停用的功能不应在 flush 时又抽一批
            android.util.Log.d("ChatRepository", "Long-term memory disabled for this session")
            synchronized(pendingExtractionLock) { pendingExtractions.remove(sessionId) }
            return
        }

        val batch: PendingExtractionBatch? =
            synchronized(pendingExtractionLock) {
                val buffer =
                    pendingExtractions.getOrPut(sessionId) {
                        PendingExtractionBuffer(ownerId, characterId, providerId)
                    }
                buffer.providerId = providerId
                buffer.turns.add(PendingExtractionTurn(userMessage, assistantMessage, messageIds))
                if (buffer.turns.size >= MEMORY_EXTRACTION_BATCH_TURNS) {
                    pendingExtractions.remove(sessionId)
                    PendingExtractionBatch(ownerId, characterId, sessionId, providerId, buffer.turns.toList())
                } else {
                    android.util.Log.d(
                        "ChatRepository",
                        "Memory extraction buffered: session=$sessionId, pending=${buffer.turns.size}",
                    )
                    null
                }
            }
        if (batch != null) launchMemoryExtraction(batch)
    }

    /**
     * 排空全部会话的抽取缓冲（不足一批的尾巴也立即抽取）。
     * fire-and-forget：抽取走独立 backgroundScope，可在 ViewModel.onCleared 等不可挂起的时机调用；
     * 进程被强杀时最多丢一批缓冲（原文仍在消息库），语义同改动前的逐轮抽取失败。
     */
    fun flushPendingMemoryExtractions() {
        val batches: List<PendingExtractionBatch> =
            synchronized(pendingExtractionLock) {
                val drained =
                    pendingExtractions
                        .map { (sessionId, buffer) ->
                            PendingExtractionBatch(
                                buffer.ownerId,
                                buffer.characterId,
                                sessionId,
                                buffer.providerId,
                                buffer.turns.toList(),
                            )
                        }.filter { it.turns.isNotEmpty() }
                pendingExtractions.clear()
                drained
            }
        batches.forEach { launchMemoryExtraction(it) }
    }

    /** 会话被删除/清空时丢弃其缓冲：源消息已不存在，抽出结果的 relatedMessageIds 只会指向悬空 id */
    fun discardPendingMemoryExtractions(sessionId: String) {
        synchronized(pendingExtractionLock) { pendingExtractions.remove(sessionId) }
    }

    /**
     * 消息被撤销/回退/重生覆盖后修剪缓冲：凡引用了已不存在消息的待发轮次整轮剔除——
     * 缺了另一半的对话抽出来也是失真记忆（regenerate 与改动前一致，新回复不进缓冲）。
     */
    private fun prunePendingExtractionTurns(
        sessionId: String,
        remainingMessageIds: Set<String>,
    ) {
        synchronized(pendingExtractionLock) {
            val buffer = pendingExtractions[sessionId] ?: return
            buffer.turns.removeAll { turn -> turn.messageIds.any { it !in remainingMessageIds } }
            if (buffer.turns.isEmpty()) pendingExtractions.remove(sessionId)
        }
    }

    private fun launchMemoryExtraction(batch: PendingExtractionBatch) {
        backgroundScope.launch {
            try {
                android.util.Log.d(
                    "ChatRepository",
                    "Long-term memory enabled, extracting memories for ${batch.turns.size} turn(s)...",
                )

                val extractionConfig = resolveExtractionConfig(batch.providerId)

                val savedCount =
                    memoryExtractionService.extractAndSaveMemories(
                        turns =
                            batch.turns.map {
                                MemoryExtractionService.DialogueTurn(
                                    userMessage = it.userMessage,
                                    assistantMessage = it.assistantMessage,
                                    messageIds = it.messageIds,
                                )
                            },
                        ownerId = batch.ownerId,
                        characterId = batch.characterId,
                        sessionId = batch.sessionId,
                        config = extractionConfig,
                    )
                android.util.Log.d("ChatRepository", "Memory extraction completed, saved $savedCount memories")
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "Memory extraction failed: ${e.message}", e)
            }
        }
    }

    // 解析记忆抽取用的 LLM 配置：会话指定配置 → api_configs 默认配置 → 活跃 LLM 配置兜底；都没有则返回 null（抽取跳过）
    private suspend fun resolveExtractionConfig(providerId: String): LlmConfig? {
        val dao = db.apiConfigDao()
        val matched = if (providerId.isNotBlank()) dao.getById(providerId) else null
        val fallback = matched ?: dao.getDefault()
        if (fallback != null) {
            android.util.Log.d("ChatRepository", "Memory extraction using config: ${fallback.name}")
            return LlmConfig(
                baseUrl = fallback.apiUrl,
                apiKey = fallback.apiKey,
                model = fallback.model,
            )
        }

        val llmConfig = loadLlmConfig()
        return if (
            llmConfig.baseUrl.isNotBlank() &&
            llmConfig.apiKey.isNotBlank() &&
            llmConfig.model.isNotBlank()
        ) {
            android.util.Log.i("ChatRepository", "Using active LLM config for memory extraction")
            llmConfig
        } else {
            android.util.Log.w("ChatRepository", "No LLM configs available, memory extraction disabled")
            null
        }
    }
}
