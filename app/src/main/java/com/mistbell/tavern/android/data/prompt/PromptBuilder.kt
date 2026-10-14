package com.mistbell.tavern.android.data.prompt

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.model.GroupChatContext
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.data.memory.MemoryConflictResolver
import com.mistbell.tavern.android.data.memory.MemoryRanking
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.data.repository.LexicalMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.util.MacroContext
import com.mistbell.tavern.android.util.MacroEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

object PromptBuilder {
    /** World Info 的会话内运行状态：sticky/cooldown/delay 只影响激活决策，不改变 Room 主数据。 */
    private object WorldInfoRuntime {
        private const val MAX_RECURSION_DEPTH = 3

        private data class EntryState(var lastTurn: Int = -1, var sticky: Boolean = false)

        private data class SessionState(var turn: Int = 0, val entries: MutableMap<String, EntryState> = mutableMapOf())

        private data class Candidate(
            val entry: WorldBookEntryEntity,
            val key: String,
            val score: Int,
            val primaryHits: Int,
            val secondaryHits: Int,
            val sticky: Boolean,
        )

        private val sessions = mutableMapOf<String, SessionState>()

        /**
         * Activates World Info for one prompt.  Matching is recursive: content activated in
         * an earlier pass becomes scan material for the next pass, bounded to three passes.
         * Secondary keys are an AND gate (every non-empty secondary key must match).  Entries
         * sharing a non-empty group compete by score and only the highest scoring entry wins.
         */
        @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
        @Synchronized
        fun activate(
            sessionId: String,
            entries: List<WorldBookEntryEntity>,
            scanText: String,
        ): Set<String> {
            val state = sessions.getOrPut(sessionId) { SessionState() }
            state.turn += 1
            val activated = linkedSetOf<String>()
            val selectedGroups = mutableSetOf<String>()
            var corpus = scanText
            repeat(MAX_RECURSION_DEPTH) {
                val candidates =
                    entries
                        .asSequence()
                        .filter { !it.disable && !it.constant }
                        .filter { entry ->
                            val key = "${entry.bookId}:${entry.id}"
                            key !in activated && (entry.groupName.isBlank() || entry.groupName !in selectedGroups)
                        }.mapNotNull { entry ->
                            val key = "${entry.bookId}:${entry.id}"
                            val entryState = state.entries.getOrPut(key) { EntryState() }
                            val primaryHits = matchingKeywords(entry.keysJson, corpus)
                            val secondaryKeys = parseKeywords(entry.secondaryKeysJson)
                            val secondaryHits = matchingKeywords(secondaryKeys, corpus)
                            val primaryMatched = primaryHits > 0
                            val secondaryMatched = secondaryKeys.isEmpty() || secondaryHits == secondaryKeys.size
                            val stickyMatched = entry.sticky && entryState.sticky
                            // delay 表示需要等待的轮数；0 代表当前轮即可激活。
                            val delayed = state.turn > entry.delay.coerceAtLeast(0)
                            val cooledDown =
                                entry.cooldown <= 0 ||
                                    entryState.lastTurn < 0 ||
                                    state.turn - entryState.lastTurn > entry.cooldown
                            if ((!stickyMatched && !(primaryMatched && secondaryMatched)) || !delayed || !cooledDown) {
                                null
                            } else {
                                Candidate(
                                    entry = entry,
                                    key = key,
                                    score = primaryHits * 2 + secondaryHits + if (stickyMatched) 1 else 0,
                                    primaryHits = primaryHits,
                                    secondaryHits = secondaryHits,
                                    sticky = stickyMatched,
                                )
                            }
                        }.toList()
                if (candidates.isEmpty()) return@repeat
                val winners =
                    candidates
                        .groupBy { it.entry.groupName }
                        .values
                        .flatMap { group ->
                            if (group.first().entry.groupName.isBlank()) {
                                group
                            } else {
                                listOf(
                                    group.maxWithOrNull(
                                        compareBy<Candidate> { it.score }
                                            .thenByDescending { it.primaryHits }
                                            .thenBy { it.entry.order },
                                    )!!,
                                )
                            }
                        }.sortedWith(compareBy<Candidate> { it.entry.order }.thenBy { it.key })
                if (winners.isEmpty()) return@repeat
                val newlyActivated = mutableListOf<String>()
                winners.forEach { candidate ->
                    val entry = candidate.entry
                    if (entry.probability < 1.0 &&
                        kotlin.random.Random.nextDouble() >= entry.probability
                    ) {
                        return@forEach
                    }
                    activated += candidate.key
                    newlyActivated += candidate.key
                    entry.groupName.takeIf { it.isNotBlank() }?.let(selectedGroups::add)
                    val entryState = state.entries.getOrPut(candidate.key) { EntryState() }
                    entryState.lastTurn = state.turn
                    entryState.sticky = entry.sticky
                }
                if (newlyActivated.isEmpty()) return@repeat
                corpus +=
                    "\\n" +
                    entries
                        .filter { "${it.bookId}:${it.id}" in newlyActivated }
                        .joinToString("\\n") { it.content }
            }
            return activated
        }

        private fun parseKeywords(keysJson: String): List<String> =
            runCatching {
                if (keysJson.isBlank()) emptyList() else Json.decodeFromString<List<String>>(keysJson)
            }.getOrDefault(emptyList())

        private fun matchingKeywords(
            keysJson: String,
            text: String,
        ): Int = matchingKeywords(parseKeywords(keysJson), text)

        private fun matchingKeywords(
            keys: List<String>,
            text: String,
        ): Int =
            keys.count { key ->
                val normalized = key.trim()
                if (normalized.isBlank()) {
                    false
                } else if (normalized.startsWith("/") && normalized.endsWith("/")) {
                    runCatching {
                        Regex(normalized.substring(1, normalized.length - 1), RegexOption.IGNORE_CASE)
                            .containsMatchIn(text)
                    }.getOrDefault(false)
                } else if (normalized.contains("*")) {
                    val pattern = normalized.split("*").joinToString(".*") { Regex.escape(it) }
                    runCatching { Regex("(?is).*" + pattern + ".*").matches(text) }.getOrDefault(false)
                } else {
                    text.contains(normalized, ignoreCase = true)
                }
            }
    }
    // ---- 群聊模式常量（classic 模式不进入任何 group 分支，提示词一个字符都不变——硬约束）----

    // 群聊行为规范块的内容与默认模板已移入 ChatSettingsResolver（可配置 + 单一默认值来源）：
    // 模板必须包含前缀格式（『角色名:』半角冒号）、不替用户/他人发言、一次只发言一位，
    // 以及历史前缀格式说明（历史每行已带『名字:』前缀，模型照此格式续写即可提高命中率）

    // 目标角色推动语模板（targetSpeakerId 非空时追加一行）
    private const val GROUP_CHAT_TARGET_TEMPLATE = "（接下来请由 %s 回应）"

    // ---- 提示词模板段的来源标签（「查看提示词」抽屉逐段归因用）----
    private const val SOURCE_MAIN_PROMPT = "主提示词"
    private const val SOURCE_PERSONA = "用户人设"
    private const val SOURCE_POST_HISTORY = "历史后指令"
    private const val SOURCE_GROUP_RULES = "群聊规范"

    // 历史归属兜底：speakerNames 查不到的 character_id 取前 4 位，仍为空则用通用名
    private const val GROUP_SPEAKER_NAME_FALLBACK_LENGTH = 4
    private const val GROUP_SPEAKER_FALLBACK_NAME = "角色"

    // 记忆重复注入判定的最小长度：短于该值的正文不做"内容包含"比对，避免误伤不同事实
    private const val MIN_DUPLICATE_LENGTH = 8
    private const val MEMORY_CANDIDATE_LIMIT = 100
    private const val RRF_RANK_CONSTANT = 60.0
    private const val MIN_HYBRID_VECTOR_SCORE = 0.5f
    private const val MAX_LEXICAL_CONTENT_CHARS = 240
    private const val HISTORY_TIMESTAMP_PREFIX_LENGTH = 16
    private const val VECTOR_RECALL_MULTIPLIER = 2

    private data class HybridCandidate(
        val content: String,
        var score: Double,
        val sources: MutableSet<String>,
    )

    private data class HybridRecallRequest(
        val ownerId: String,
        val characterId: String,
        val sessionId: String,
        val userMessage: String,
        val alreadyInjected: List<String>,
    )

    private val THINKING_BLOCK_REGEX = Regex("(?s)<think>[\\s\\S]*?</think>")

    // ---- 提示词溯源（聊天页「查看提示词」用） ----

    /**
     * 提示词中的一段及其来源标签。
     *
     * 装配过程内部一律用 [Segment] 累积（而不是裸 [ChatMessage]），末尾再统一转成
     * [ChatMessage] 列表——保证「预览看到的」与「真实发出去的」是**同一次装配的产物**，
     * 不存在两份会各自漂移的实现。
     */
    data class Segment(
        val message: ChatMessage,
        /** 来源标签，如「角色卡」「世界书·角色定义后」「长期记忆」 */
        val source: String,
        /** World Info 激活原因；普通提示词段为空。 */
        val activationReason: String? = null,
        /** 实际插入锚点或 @D 位置。 */
        val insertionPosition: String? = null,
        /** 该段被裁剪/降级的原因；未裁剪时为空。 */
        val truncationReason: String? = null,
        /** 从统一 ChatMessage 转换到提供商协议后的结果摘要。 */
        val protocolConversionResult: String? = null,
    )

    /** 一次装配的完整结果：分段（含来源）与总量估算 */
    data class PromptTrace(
        val segments: List<Segment>,
        val totalEstimatedTokens: Int,
        val truncationReasons: List<String> = emptyList(),
        val protocolConversionResults: List<String> = emptyList(),
        /** Fingerprint of stable prompt configuration; stale snapshots are never replayed. */
        val cacheFingerprint: String = "",
        /** Exact dynamic prompt suffix for provider-compatible prefix-cache replay in the next turn. */
        val cacheContextSnapshot: List<ChatMessage> = emptyList(),
    ) {
        /** 单段估算 token（预览页展示用；与 PromptDiag 同一估算口径） */
        fun tokensOf(segment: Segment): Int = estimateTokensOf(segment.message.content)
    }

    private const val SOURCE_CHARACTER = "角色卡"

    /** token 估算的无状态版本（供 [Segment] 外部调用，避免暴露整个 object） */
    internal fun estimateTokensOf(text: String): Int = estimateTokens(text)

    /** 追加一段（带来源标签） */
    private fun MutableList<Segment>.addSegment(
        role: String,
        content: String,
        source: String,
    ) {
        val isWorldInfo = source.startsWith("世界书")
        add(
            Segment(
                message = ChatMessage(role = role, content = content),
                source = source,
                activationReason =
                    when {
                        source.contains("激活") -> "关键词/次关键词命中且通过概率"
                        isWorldInfo -> "constant 常驻"
                        else -> null
                    },
                insertionPosition = source.removePrefix("世界书·").takeIf { isWorldInfo },
            ),
        )
    }

    // ---- 提示词模板段（纯函数，internal 仅为单元测试开放）----
    // 三者的共同契约：**空值不产生消息**（不注入空段），保证四项全空时 classic 提示词与改动前逐字节一致

    /** 全局主提示词 → 段；未设置返回 null。内容过宏渲染（支持 {{char}}/{{user}}/{{persona}} 等） */
    internal fun mainPromptSegment(
        raw: String?,
        mctx: MacroContext,
    ): Segment? =
        raw?.takeIf { it.isNotBlank() }?.let {
            Segment(ChatMessage(role = "system", content = MacroEngine.render(it, mctx)), SOURCE_MAIN_PROMPT)
        }

    /** 用户人设（`{{persona}}` 内容）→ 段；未设置返回 null */
    internal fun personaSegment(
        raw: String?,
        mctx: MacroContext,
    ): Segment? =
        raw?.takeIf { it.isNotBlank() }?.let {
            Segment(ChatMessage(role = "system", content = MacroEngine.render(it, mctx)), SOURCE_PERSONA)
        }

    /**
     * 角色卡 `post_history_instructions` → 段；字段缺失/空白/`data_json` 损坏均返回 null。
     *
     * @param charDataJson 角色实体的 `data_json`（内含 CharacterData）
     * @param fallbackChar 解析失败时用于宏渲染的 {{char}} 兜底名（取角色实体名，避免宏未展开）
     */
    internal fun postHistorySegment(
        charDataJson: String,
        fallbackChar: String,
        mctx: MacroContext,
    ): Segment? {
        if (charDataJson.isBlank()) return null
        val instructions =
            try {
                kotlinx.serialization.json.Json
                    .decodeFromString<com.mistbell.tavern.android.data.api.model.CharacterData>(charDataJson)
                    .postHistoryInstructions
            } catch (_: Exception) {
                // data_json 损坏：与角色卡内 systemPrompt 的解析一样静默丢弃，绝不让装配失败
                return null
            }
        if (instructions.isBlank()) return null
        // fallbackChar 仅在 mctx.char 为空时兜底，保证 {{char}} 不会渲染成空串
        val ctx = if (mctx.char.isBlank()) mctx.copy(char = fallbackChar) else mctx
        return Segment(
            ChatMessage(role = "system", content = MacroEngine.render(instructions, ctx)),
            SOURCE_POST_HISTORY,
        )
    }

    // token 估算的字符/token 比率：ASCII 按 4；CJK 按 **1.5**（实测标定，见下）。
    //
    // 标定依据（设备实测对拍，非推断）：同一份提示词 估算 16,663 vs API 实发 13,222 token，
    // 反解得 CJK 真实比率 ≈ 1.505 字符/token。三档对比：
    //   1.15 → 高估 +26%（历史被白裁，配置 1M 也只能装进 2.4K 历史）
    //   1.5  → 偏差 +0.3%（采用）
    //   1.6  → 低估  −5%
    // 注意：此前的"1.6 → 1.15 校准"基于错误归因（把"固定内容永不裁剪导致的实发超配置"
    // 当成"估算偏低"），实测证明 1.6 只差 5%，1.15 反而高估 26%
    private const val ASCII_CHARS_PER_TOKEN = 4.0
    private const val CJK_CHARS_PER_TOKEN = 1.5

    @Serializable
    private data class PromptCacheSnapshotEnvelope(
        val fingerprint: String,
        val messages: List<ChatMessage>,
    )

    private const val PROMPT_CACHE_SNAPSHOT_MAX_TOKENS = 4096
    private const val PROMPT_CACHE_REPLY_RESERVE_TOKENS = 768
    private const val MIN_HISTORY_BUDGET_TOKENS = 256
    private const val HISTORY_MESSAGE_OVERHEAD_TOKENS = 4

    suspend fun buildPrompt(
        db: AppDatabase,
        ownerId: String,
        characterId: String,
        sessionId: String,
        userMessage: String,
        currentMessageId: String? = null,
        // 重新生成场景：截断该消息及其之后的全部历史（按查询返回的时间序），
        // 保证正要被替换的旧 assistant 回复不进入上下文
        excludeFromMessageId: String? = null,
        // 群聊上下文（默认 null = classic 模式，行为与改动前完全一致）
        groupContext: GroupChatContext? = null,
        // continueGroupChat 场景的系统级推动语（由 ChatRepository 传入，本类不做特判）；
        // 非 null 时不再追加最终用户消息，改为注入这条 system 消息
        groupNudge: String? = null,
    ): List<ChatMessage> {
        val trace =
            buildPromptTrace(
                db,
                ownerId,
                characterId,
                sessionId,
                userMessage,
                currentMessageId,
                excludeFromMessageId,
                groupContext,
                groupNudge,
            )
        if (!currentMessageId.isNullOrBlank() && groupNudge == null) {
            val snapshotJson =
                Json.encodeToString(
                    PromptCacheSnapshotEnvelope.serializer(),
                    PromptCacheSnapshotEnvelope(
                        fingerprint = trace.cacheFingerprint,
                        messages = trace.cacheContextSnapshot,
                    ),
                )
            // Cache snapshots are an optimization only: a failed write must not fail chat.
            try {
                db.messageDao().updatePromptContextSnapshot(
                    messageId = currentMessageId,
                    sessionId = sessionId,
                    ownerId = ownerId,
                    snapshotJson = snapshotJson,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PromptCache", "Could not persist prompt cache snapshot", e)
            }
        }
        return trace.segments.map { it.message }
    }

    /**
     * 装配并返回带来源标注的提示词（聊天页「查看提示词」入口）。
     *
     * 与 [buildPrompt] **共用同一条装配路径**：本函数是唯一实现，buildPrompt 只是取其
     * `segments.map { it.message }`，所以预览内容与真实请求逐字节一致。
     */
    suspend fun buildPromptTrace(
        db: AppDatabase,
        ownerId: String,
        characterId: String,
        sessionId: String,
        userMessage: String,
        currentMessageId: String? = null,
        excludeFromMessageId: String? = null,
        groupContext: GroupChatContext? = null,
        groupNudge: String? = null,
    ): PromptTrace {
        val messages = mutableListOf<Segment>()

        val session = db.sessionDao().get(sessionId, ownerId, characterId)
        // 三态覆盖语义（SETTINGS.md 分层原则）：会话显式值 > 全局当前默认 > 内置兜底，
        // 统一经 ChatSettingsResolver 解析——不再在此硬编码 4096 兜底
        val settingsDao = db.settingsDao()
        // Prefix-snapshot replay is valid for OpenAI-compatible message protocols.
        // Model IDs, API URLs, and API keys are deliberately excluded from the content fingerprint:
        // switching between compatible models should not discard identical historical prompt text.
        val cacheProviderConfig =
            session?.providerId
                ?.takeIf { it.isNotBlank() }
                ?.let { db.apiConfigDao().getById(it) }
                ?: db.apiConfigDao().getDefault()
        val cacheSnapshotEnabled =
            cacheProviderConfig == null || cacheProviderConfig.type == "openai" || cacheProviderConfig.type == "custom"
        val contextTokenLimit =
            ChatSettingsResolver.contextTokenLimit(
                session,
                ChatSettingsResolver.globalDefaultContextTokens(settingsDao.getValue("default_context_tokens")),
            )
        val longTermMemoryEnabled =
            ChatSettingsResolver.longTermMemoryEnabled(
                session,
                ChatSettingsResolver.globalDefaultLtmEnabled(settingsDao.getValue("default_ltm_enabled")),
            )
        val participantCharacterIds = session?.participantCharacterIds() ?: listOf(characterId)
        val participantById =
            db.characterDao()
                .getByIds(participantCharacterIds.distinct())
                .associateBy { it.id }
        val participantCharacters =
            participantCharacterIds
                .mapNotNull { participantById[it] }
                .ifEmpty { participantById[characterId]?.let { listOf(it) } ?: emptyList() }
        val character = participantCharacters.firstOrNull() ?: participantById[characterId]
        // F2.1 宏引擎上下文（契约 B）：用户名与人设统一取全局设置（键常量收敛在 ChatSettingsResolver，
        // 禁止手写字面量）；用户名缺省 "User"，人设缺省空串（不注入该段）
        val userName = ChatSettingsResolver.userName(settingsDao.getValue(ChatSettingsResolver.KEY_USER_NAME))
        val userPersona = ChatSettingsResolver.userPersona(settingsDao.getValue(ChatSettingsResolver.KEY_USER_PERSONA))
        val mainPrompt = ChatSettingsResolver.mainPrompt(settingsDao.getValue(ChatSettingsResolver.KEY_MAIN_PROMPT))
        val mctx =
            MacroContext(
                char = character?.name ?: "",
                user = userName,
                description = character?.description ?: "",
                personality = character?.personality ?: "",
                scenario = character?.scenario ?: "",
                persona = userPersona,
            )
        if (character != null) {
            val systemParts = mutableListOf<String>()
            if (participantCharacters.size > 1) {
                systemParts.add(
                    "This is a multi-character chat. Primary speaker: ${character.name}. " +
                        "Other selected characters may participate when appropriate: " +
                        participantCharacters.drop(1).joinToString(", ") { it.name } + ".",
                )
            }
            participantCharacters.forEachIndexed { index, participant ->
                val roleLabel = if (index == 0) "Primary character" else "Participant character"
                val characterParts = mutableListOf<String>()
                characterParts.add("$roleLabel: ${participant.name}")
                // 参与组装的角色文本先过宏引擎渲染（{{char}}/{{user}} 等）
                if (participant.description.isNotBlank()) {
                    characterParts.add(MacroEngine.render(participant.description, mctx))
                }
                if (participant.personality.isNotBlank()) {
                    characterParts.add(
                        "Personality: ${MacroEngine.render(participant.personality, mctx)}",
                    )
                }
                if (participant.scenario.isNotBlank()) {
                    characterParts.add("Scenario: ${MacroEngine.render(participant.scenario, mctx)}")
                }
                if (participant.dataJson.isNotBlank()) {
                    try {
                        val charData =
                            kotlinx.serialization.json.Json.decodeFromString<com.mistbell.tavern.android.data.api.model.CharacterData>(
                                participant.dataJson,
                            )
                        if (charData.systemPrompt.isNotBlank()) {
                            characterParts.add(1, MacroEngine.render(charData.systemPrompt, mctx))
                        }
                    } catch (_: Exception) {
                    }
                }
                systemParts.add(characterParts.joinToString("\n"))
            }
            if (systemParts.isNotEmpty()) {
                messages.addSegment("system", systemParts.joinToString("\n\n"), SOURCE_CHARACTER)
            }
        }

        // 用户人设（`{{persona}}` 宏内容）：紧跟角色卡之后，让模型先知己方（AI）再知彼方（用户）。
        // 全局稳定内容 → 落在历史之前，随前缀缓存命中
        personaSegment(userPersona, mctx)?.let { messages.add(it) }

        // 群聊模式：紧跟角色 system 文本追加行为规范块 + 目标角色推动（classic 模式 groupContext 为 null，不进入）
        // 规范模板可配置（settings：group_chat_rules_prompt），留空回落内置默认；{user} 占位符按用户名替换
        if (groupContext != null) {
            // 顺序敏感：先宏渲染，再替换 {user} 占位符——{user} 是 {{user}} 的子串，
            // 反过来的话自定义模板里的 {{user}} 会被啃坏（详见 applyUserNamePlaceholder 注释）
            val rules =
                ChatSettingsResolver.applyUserNamePlaceholder(
                    MacroEngine.render(
                        ChatSettingsResolver.groupChatRules(
                            settingsDao.getValue(ChatSettingsResolver.KEY_GROUP_CHAT_RULES),
                        ),
                        mctx,
                    ),
                    mctx.user,
                )
            val targetName = groupContext.targetSpeakerId?.let { groupContext.speakerNames[it] }
            val groupSystem =
                if (targetName != null) {
                    "$rules\n" + GROUP_CHAT_TARGET_TEMPLATE.format(targetName)
                } else {
                    rules
                }
            messages.addSegment("system", groupSystem, SOURCE_GROUP_RULES)
        }

        // 世界书（v19 起 insertPosition/depth 真正参与装配）。解析须在 LTM 之前完成：
        // afterPrompt 条目要落在"角色定义/群聊规范之后、LTM 之前"的位置
        // 会话级世界书优先；其次回退到角色卡默认；最后回退到全局 "main"
        val worldBookIds =
            listOfNotNull(
                session?.worldBookId?.takeIf { it.isNotBlank() },
                character?.worldBookId?.takeIf { it.isNotBlank() },
                "main",
            ).distinct()
        val worldBookEntries =
            worldBookIds
                .flatMap { db.worldBookDao().getEntriesList(it) }
                .distinctBy { it.bookId to it.id }
        val worldInfoScanText =
            buildString {
                appendLine(userMessage)
                db.messageDao().getBySession(sessionId, ownerId).first()
                    .takeLast(6)
                    .forEach { appendLine(it.content) }
            }
        // 关键词激活判定（与位置规划正交）：非常驻、启用、概率掷骰通过、命中当前用户消息任一关键词；
        // probability=1（缺省）跳过掷骰快速路径，constant 常驻条目不参与本判定（恒注入）
        val activatedEntryIds =
            WorldInfoRuntime.activate(sessionId, worldBookEntries, worldInfoScanText)
        val wbPlan = WorldBookPlacement.plan(worldBookEntries)
        // 缓存优化：只注入 constant 条目到历史之前，activated 条目稍后注入到历史之后
        worldInfoBlock(wbPlan.afterPrompt, activatedEntryIds, mctx, constantOnly = true)?.let {
            messages.addSegment("system", it, "世界书·角色定义后")
        }
        worldInfoBlock(wbPlan.beforePrompt, activatedEntryIds, mctx, constantOnly = true)?.let {
            // add(0)：beforePrompt 条目压到整条提示词最前（角色定义之前）
            messages.add(0, Segment(ChatMessage(role = "system", content = it), "世界书·角色定义前"))
        }

        // 全局主提示词（settings：main_prompt）：所有会话共用的基础系统提示。
        // 同样走 add(0) 且**在本处最后执行** → 落在 index 0，得到酒馆顺序
        // [主提示词][世界书·角色定义前][角色卡]…。全局稳定内容 → 落在历史之前，随前缀缓存命中
        mainPromptSegment(mainPrompt, mctx)?.let { messages.add(0, it) }

        // 示例消息（mes_example）：角色定义后的示例对话块；EM 锚点（示例消息前/后）分列其两侧。
        // 群聊取主角色示例；示例内容同样过宏渲染
        // 缓存优化：只注入 constant 条目到历史之前
        val mesExample = character?.mesExample?.trim().orEmpty()
        worldInfoBlock(wbPlan.emBefore, activatedEntryIds, mctx, constantOnly = true)?.let {
            messages.addSegment("system", it, "世界书·示例消息前")
        }
        if (mesExample.isNotEmpty()) {
            messages.addSegment("system", "Example dialogue:\n${MacroEngine.render(mesExample, mctx)}", "示例对话")
        }
        worldInfoBlock(wbPlan.emAfter, activatedEntryIds, mctx, constantOnly = true)?.let {
            messages.addSegment("system", it, "世界书·示例消息后")
        }

        val cacheFingerprint =
            promptCacheFingerprint(
                buildList {
                    add("prompt-cache-fingerprint-v1")
                    add(ownerId)
                    add(characterId)
                    add(sessionId)
                    add(contextTokenLimit.toString())
                    add(longTermMemoryEnabled.toString())
                    add(session?.authorNote.orEmpty())
                    add(session?.worldBookId.orEmpty())
                    add(session?.mode.orEmpty())
                    add(session?.modeConfigJson.orEmpty())
                    // Group mode and target/name changes alter historical speaker prefixes.
                    add((groupContext != null).toString())
                    add(groupContext?.targetSpeakerId.orEmpty())
                    groupContext?.speakerNames?.toSortedMap()?.forEach { (speakerId, speakerName) ->
                        add(speakerId)
                        add(speakerName)
                    }
                    add(userName)
                    add(userPersona)
                    add(mctx.char)
                    add(mctx.description)
                    add(mctx.personality)
                    add(mctx.scenario)
                    participantCharacters.forEach { participant ->
                        add(participant.id)
                        add(participant.name)
                        add(participant.description)
                        add(participant.personality)
                        add(participant.scenario)
                        add(participant.mesExample)
                        add(participant.worldBookId)
                        add(participant.dataJson)
                    }
                    worldBookEntries
                        .sortedWith(compareBy<WorldBookEntryEntity>({ it.bookId }, { it.order }, { it.id }))
                        .forEach { entry ->
                            add(entry.bookId)
                            add(entry.id)
                            add(entry.comment)
                            add(entry.keysJson)
                            add(entry.secondaryKeysJson)
                            add(entry.content)
                            add(entry.constant.toString())
                            add(entry.disable.toString())
                            add(entry.order.toString())
                            add(entry.insertPosition)
                            add(entry.depth.toString())
                            add(entry.probability.toString())
                            add(entry.depthRole)
                            add(entry.sticky.toString())
                            add(entry.cooldown.toString())
                            add(entry.delay.toString())
                            add(entry.groupName)
                        }
                    messages.forEach { segment ->
                        add(segment.message.role)
                        add(segment.message.content)
                    }
                },
            )

        // 长期记忆（结构化召回 + 向量/词法召回）：**先构建、暂不注入**。
        //
        // 注入点在历史之后、最终用户消息之前（见 @D 块之后）：召回集合由当前用户消息决定，
        // 逐轮都变；若排在历史之前会打断「角色卡 + 历史」这段逐字节稳定的前缀，
        // 使前缀缓存每轮全量未命中——这是"比酒馆更消耗"的结构性原因之一。
        //
        // 预算仍按**含记忆块**的口径计算（下方传 messages + memoryContext.allMessages()），
        // 保证历史窗口与改动前完全一致，不会因记忆块后移而撑爆上下文
        val memoryContext =
            if (longTermMemoryEnabled) {
                buildMemoryContext(db, ownerId, characterId, sessionId, userMessage)
            } else {
                MemoryContext()
            }

        // 历史源会话级读取（跨代理契约 2）：会话是消息的完整归属单元，character_id 仅作
        // 说话方元数据（群聊），历史一律按 (session_id, owner_id) 全量取——群聊 NPC 消息
        // 与主角色消息同窗进入上下文，说话方由 annotateGroupHistory 加前缀区分
        var historySource: List<MessageEntity> = db.messageDao().getBySession(sessionId, ownerId).first()
        if (excludeFromMessageId != null) {
            val idx = historySource.indexOfFirst { it.id == excludeFromMessageId }
            if (idx >= 0) {
                historySource = historySource.subList(0, idx)
            }
        }
        val recentMessages =
            historySource
                // 过滤掉刚落库的当前用户消息，避免同一条消息在 prompt 中重复出现
                .filter { currentMessageId == null || it.id != currentMessageId }
        val promptCacheSnapshots: MutableMap<String, List<ChatMessage>> =
            if (cacheSnapshotEnabled) {
                recentMessages.mapNotNull { message ->
                    val rawSnapshot = message.promptContextSnapshotJson
                    if (message.role != "user" || rawSnapshot.isBlank()) {
                        null
                    } else {
                        runCatching {
                            val snapshot =
                                Json.decodeFromString(
                                    PromptCacheSnapshotEnvelope.serializer(),
                                    rawSnapshot,
                                )
                            message.id to snapshot.takeIf { it.fingerprint == cacheFingerprint }?.messages
                        }.getOrNull()?.let { (messageId, snapshotMessages) ->
                            snapshotMessages?.takeIf { it.isNotEmpty() }?.let { messageId to it }
                        }
                    }
                }.toMap().toMutableMap()
            } else {
                mutableMapOf()
            }
        val currentMessagesForBudget = messages.map { it.message } + memoryContext.allMessages()
        val fixedTokens = currentMessagesForBudget.sumOf { estimateTokens(it.content) }
        val currentUserTokens = estimateTokens(userMessage)
        val historyBudget =
            (contextTokenLimit - fixedTokens - currentUserTokens - PROMPT_CACHE_REPLY_RESERVE_TOKENS)
                .coerceAtLeast(MIN_HISTORY_BUDGET_TOKENS)
        // The newest history entry is normally kept even when over budget. Its cache snapshot,
        // however, is optional: drop that snapshot if replaying it would push an already-large
        // newest user turn over the remaining history budget (notably during regeneration).
        val newestHistoryMessage = recentMessages.lastOrNull()
        if (newestHistoryMessage?.role == "user") {
            val snapshot = promptCacheSnapshots[newestHistoryMessage.id].orEmpty()
            val savedUser = snapshot.lastOrNull()?.takeIf { it.role == "user" }
            val replayedSuffix = if (savedUser != null) snapshot.dropLast(1) else snapshot
            val snapshotTurnTokens =
                estimateTokens(savedUser?.content ?: newestHistoryMessage.content) +
                    HISTORY_MESSAGE_OVERHEAD_TOKENS +
                    replayedSuffix.sumOf { estimateTokens(it.content) + HISTORY_MESSAGE_OVERHEAD_TOKENS }
            if (snapshotTurnTokens > historyBudget) {
                promptCacheSnapshots.remove(newestHistoryMessage.id)
            }
        }
        val history: List<MessageEntity> =
            selectHistoryWithinBudget(
                recentMessages = recentMessages,
                promptCacheSnapshots = promptCacheSnapshots,
                // 含待注入记忆块的口径：记忆虽然后移到历史之后，但它同样占用上下文预算，
                // 历史窗口必须据此扣减（与改动前一致，历史选取结果不因本次重排变化）
                currentMessages = messages.map { it.message } + memoryContext.allMessages(),
                currentUserMessage = userMessage,
                contextTokenLimit = contextTokenLimit,
            )
        val historyTruncated = recentMessages.size > history.size
        val annotatedGroupHistory =
            groupContext?.let {
                // 群聊模式：历史消息加说话方前缀（AI 消息按 character_id 查名字，用户消息用 {{user}} 值）
                annotateGroupHistory(
                    history = history,
                    speakerNames = it.speakerNames,
                    userName = mctx.user,
                )
            }
        history.forEachIndexed { index, msg: MessageEntity ->
            // For OpenAI-compatible providers, replay the exact per-turn suffix immediately
            // before that historical user message, preserving the prior request's token prefix.
            val savedSnapshot = if (msg.role == "user") promptCacheSnapshots[msg.id].orEmpty() else emptyList()
            val replayedUserMessage = savedSnapshot.lastOrNull()?.takeIf { it.role == "user" }
            val replayedSuffix =
                if (replayedUserMessage != null) savedSnapshot.dropLast(1) else savedSnapshot
            replayedSuffix.forEachIndexed { snapshotIndex, snapshot ->
                messages.add(Segment(snapshot, "历史缓存快照·${snapshotIndex + 1}"))
            }
            if (annotatedGroupHistory != null) {
                messages.add(Segment(replayedUserMessage ?: annotatedGroupHistory[index], "历史·群聊"))
            } else {
                // Historical user content comes from the exact prior request when a snapshot
                // exists; otherwise keep the stored message and do not re-render old macros.
                val historicalMessage =
                    replayedUserMessage
                        ?: ChatMessage(role = msg.role, content = stripThinkingBlocks(msg.content))
                messages.add(
                    Segment(
                        historicalMessage,
                        "历史第 ${index + 1}/${history.size} 条",
                        truncationReason = if (historyTruncated) "上下文预算裁剪后保留的最近窗口" else null,
                    ),
                )
            }
        }
        val currentTurnContextStartIndex = messages.size

        // 角色卡的历史后指令（post_history_instructions）：酒馆里它插在**历史之后**，
        // 作为"最后一句指令"约束生成（走在附加指令之前——附加指令是会话级实时覆盖，仍居末位）。
        // 该字段此前可编辑可存库但从不注入（死字段），本处接通。
        // 取主角色（participantCharacters.first()）的那一份：这是一条全局收尾指令，群聊不做逐角色叠加。
        // 代价说明：它落在历史之后属易变尾部，这部分 token 不命中前缀缓存（同 @D，语义优先）
        val primaryDataJson = participantCharacters.firstOrNull()?.dataJson.orEmpty()
        postHistorySegment(primaryDataJson, character?.name.orEmpty(), mctx)?.let { messages.add(it) }

        // 会话附加指令（author_note）：非空时经宏渲染，注入在历史之后、最终用户消息之前
        val authorNote = session?.authorNote?.trim().orEmpty()
        if (authorNote.isNotEmpty()) {
            messages.addSegment("system", "【附加指令】\n${MacroEngine.render(authorNote, mctx)}", "附加指令")
        }

        // AN 锚点（作者注释前/后）：紧贴【附加指令】块上沿/下沿；会话无附加指令时
        // 两块依次落在历史之后，位置语义保持稳定
        // 缓存优化：这些块本就在历史后，但只注入 constant 条目，activated 稍后统一注入
        worldInfoBlock(wbPlan.anBefore, activatedEntryIds, mctx, constantOnly = true)?.let {
            messages.addSegment("system", it, "世界书·作者注释前")
        }
        worldInfoBlock(wbPlan.anAfter, activatedEntryIds, mctx, constantOnly = true)?.let {
            messages.addSegment("system", it, "世界书·作者注释后")
        }

        // 缓存优化：@D 深度插入改为后移到历史之后，不再插入历史内部破坏前缀稳定性。
        // 保持语义：按深度倒序排列（D=1 最后/最靠近生成点，D=10 最前），但整体在历史后统一注入
        // 注意：暂时注释掉原有的历史内插入逻辑，改为收集到 depthBlocks 列表稍后统一注入
        val depthBlocks = mutableListOf<Segment>()
        wbPlan.byDepth
            .toList()
            .sortedBy { it.first.first } // 按深度升序（D=1, D=2, ...）
            .forEach { (key, group) ->
                val (depth, role) = key
                worldInfoBlock(group, activatedEntryIds, mctx, constantOnly = false)?.let { block ->
                    depthBlocks.add(
                        Segment(
                            message = ChatMessage(role = role, content = block),
                            source = "世界书·插入深度 @D$depth",
                            activationReason = if (group.any { it.constant }) "constant 常驻" else "关键词/次关键词命中且通过概率",
                            insertionPosition = "@D$depth/$role",
                        ),
                    )
                }
            }

        // 长期记忆块：排在历史与 @D 之后、最终用户消息之前——
        // 让「角色卡 + 历史」保持逐字节稳定的前缀（前缀缓存命中率），同时记忆贴着生成点注入。
        // 会话历史**未被裁剪**时丢弃"相关历史片段"召回块：整段会话都在提示词里，
        // 召回片段必然与之重复（纯浪费 token 且拉低缓存命中率），此判定无信息损失
        messages.addAll(
            memoryContext
                .messagesFor(historyTruncated = historyTruncated)
                .map { Segment(it, "长期记忆") },
        )

        // 缓存优化：在长期记忆之后统一注入所有激活条目（非 constant）。
        // 顺序：按原语义锚点排列 afterPrompt → emBefore → emAfter → anBefore → anAfter → @D深度块，
        // 保持语义逻辑但整体后移到历史之后，不破坏前缀稳定性
        worldInfoBlock(wbPlan.afterPrompt, activatedEntryIds, mctx, constantOnly = false)?.let {
            messages.addSegment("system", it, "世界书·角色定义后（激活）")
        }
        worldInfoBlock(wbPlan.emBefore, activatedEntryIds, mctx, constantOnly = false)?.let {
            messages.addSegment("system", it, "世界书·示例消息前（激活）")
        }
        worldInfoBlock(wbPlan.emAfter, activatedEntryIds, mctx, constantOnly = false)?.let {
            messages.addSegment("system", it, "世界书·示例消息后（激活）")
        }
        worldInfoBlock(wbPlan.anBefore, activatedEntryIds, mctx, constantOnly = false)?.let {
            messages.addSegment("system", it, "世界书·作者注释前（激活）")
        }
        worldInfoBlock(wbPlan.anAfter, activatedEntryIds, mctx, constantOnly = false)?.let {
            messages.addSegment("system", it, "世界书·作者注释后（激活）")
        }
        // @D 深度块按深度升序注入（D=1 最后，最靠近生成点）
        messages.addAll(depthBlocks)

        // Keep the exact rendered final user content alongside the dynamic suffix. On replay,
        // this avoids a cache-prefix mismatch when the original input contained macros or a
        // group-speaker prefix that cannot be reconstructed from the raw database message.
        val finalUserMessage =
            if (groupNudge == null) {
                val renderedUserMessage = MacroEngine.render(userMessage, mctx)
                if (groupContext != null) {
                    groupCurrentUserMessage(userName = mctx.user, renderedUserMessage = renderedUserMessage)
                } else {
                    renderedUserMessage
                }
            } else {
                null
            }
        val candidateCacheSnapshot =
            messages.drop(currentTurnContextStartIndex).map { it.message } +
                listOfNotNull(finalUserMessage?.let { ChatMessage(role = "user", content = it) })
        val cacheContextSnapshot =
            if (
                cacheSnapshotEnabled &&
                candidateCacheSnapshot.sumOf { estimateTokens(it.content) } <= PROMPT_CACHE_SNAPSHOT_MAX_TOKENS
            ) {
                candidateCacheSnapshot
            } else {
                emptyList()
            }

        // 最后的当前用户消息参与宏渲染；continueGroupChat 场景（groupNudge 非 null）无新用户消息，
        // 改为注入系统级推动语（classic 路径 groupNudge 恒为 null，零改动）
        if (groupNudge != null) {
            messages.addSegment("system", groupNudge, "群聊推动语")
        } else {
            messages.addSegment("user", finalUserMessage.orEmpty(), "当前消息")
        }

        // 组成诊断（只记条数与估算 token，不记内容）：用于核对上下文窗口是否装得下、
        // 历史是否被裁剪、以及估算值与 API 实发 token 的偏差——排查"实发超配置/缓存命中率低"的直接依据
        val estimatedTotal = messages.sumOf { estimateTokens(it.message.content) }
        val insertedMemory = memoryContext.messagesFor(historyTruncated = historyTruncated)
        android.util.Log.d(
            "PromptDiag",
            "contextLimit=$contextTokenLimit 消息=${messages.size}条 估算=${estimatedTotal}tok " +
                "历史=${history.size}/${recentMessages.size}条(${history.sumOf { estimateTokens(it.content) }}tok) " +
                "记忆=${insertedMemory.size}块(${insertedMemory.sumOf { estimateTokens(it.content) }}tok) " +
                "历史被裁剪=$historyTruncated 召回块丢弃=${memoryContext.recallBlock != null && !historyTruncated}",
        )

        // Trace is also consumed by the preview UI.  Every segment gets a stable
        // insertion marker and an explicit conversion note, including ordinary
        // history/current-message segments that do not have World Info metadata.
        val tracedSegments =
            messages.mapIndexed { index, segment ->
                segment.copy(
                    activationReason = segment.activationReason ?: "source=${segment.source}",
                    insertionPosition = segment.insertionPosition ?: "prompt[$index]",
                    protocolConversionResult =
                        segment.protocolConversionResult
                            ?: "ChatMessage(role=${segment.message.role}, content) kept; " +
                            "provider adapter maps this role/content",
                )
            }

        return PromptTrace(
            segments = tracedSegments,
            totalEstimatedTokens = estimatedTotal,
            truncationReasons =
                if (historyTruncated) {
                    listOf("历史消息因上下文预算被裁剪：保留最近连续窗口")
                } else {
                    emptyList()
                },
            protocolConversionResults =
                listOf(
                    "OpenAI/custom: ChatMessage role/content -> messages[].role/content",
                    "Anthropic: system messages -> top-level system; user/assistant -> messages[].content",
                    "Gemini: system -> systemInstruction; assistant -> model; others -> user",
                    "Thinking remains on the unified message model and is mapped by provider response parsers",
                ),
            cacheFingerprint = cacheFingerprint,
            cacheContextSnapshot = cacheContextSnapshot,
        )
    }

    // 群聊模式当前用户消息组装（internal 仅为单元测试开放）：
    // 与历史行格式一致加 "{用户名}: " 前缀（跨代理契约 4，提高模型命中率）
    internal fun groupCurrentUserMessage(
        userName: String,
        renderedUserMessage: String,
    ): String = "$userName: $renderedUserMessage"

    // F2.1 沿用：剔除 <think>…</think> 块并 trim（classic/群聊两条历史路径共用）
    private fun stripThinkingBlocks(content: String): String {
        return THINKING_BLOCK_REGEX.replace(content, "").trim()
    }

    /**
     * 群聊历史组装：为每条历史消息加说话方前缀，转成 ChatMessage。
     * - assistant 消息前加 "{说话角色名}: "（character_id 查 speakerNames，查不到取 id 前 4 位，仍空则"角色"）；
     *   群聊落库时 character_id 已写为实际发言的 NPC id、内容已剥前缀，此处还原说话方供模型区分角色；
     * - 其余角色（user）消息前加 "{用户名}: "；
     * - 同样剔除 <think>…</think> 块。
     * internal 仅为单元测试开放（JVM 可测：MessageEntity/ChatMessage 均为纯 Kotlin 类型）。
     */
    internal fun annotateGroupHistory(
        history: List<MessageEntity>,
        speakerNames: Map<String, String>,
        userName: String,
    ): List<ChatMessage> =
        history.map { msg ->
            val cleanContent = stripThinkingBlocks(msg.content)
            val annotated =
                if (msg.role == "assistant") {
                    "${resolveGroupSpeakerName(msg.characterId, speakerNames)}: $cleanContent"
                } else {
                    "$userName: $cleanContent"
                }
            ChatMessage(role = msg.role, content = annotated)
        }

    // 群聊历史归属兜底：speakerNames 查不到时取 character_id 前 4 位；
    // 不足 4 位的短 id 没有辨识度（如 "x"），用通用名"角色"
    private fun resolveGroupSpeakerName(
        characterId: String,
        speakerNames: Map<String, String>,
    ): String =
        speakerNames[characterId]
            ?: characterId.take(GROUP_SPEAKER_NAME_FALLBACK_LENGTH)
                .takeIf { it.length == GROUP_SPEAKER_NAME_FALLBACK_LENGTH }
            ?: GROUP_SPEAKER_FALLBACK_NAME

    // internal 仅为单元测试开放（ROADMAP M2-2：token 预算截断逻辑需要回归测试）
    internal fun promptCacheFingerprint(fields: List<String>): String {
        val canonical =
            buildString {
                fields.forEach { field ->
                    append(field.length)
                    append(':')
                    append(field)
                }
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
    }

    private const val BYTE_MASK = 0xff

    internal fun selectHistoryWithinBudget(
        recentMessages: List<MessageEntity>,
        currentMessages: List<ChatMessage>,
        currentUserMessage: String,
        contextTokenLimit: Int,
        promptCacheSnapshots: Map<String, List<ChatMessage>> = emptyMap(),
    ): List<MessageEntity> {
        val fixedTokens = currentMessages.sumOf { estimateTokens(it.content) }
        val currentUserTokens = estimateTokens(currentUserMessage)
        val historyBudget =
            (contextTokenLimit - fixedTokens - currentUserTokens - PROMPT_CACHE_REPLY_RESERVE_TOKENS)
                .coerceAtLeast(MIN_HISTORY_BUDGET_TOKENS)

        val selected = ArrayDeque<MessageEntity>()
        var usedTokens = 0

        // 从最新往回连续选取，预算耗尽即停止，保证历史片段连续；
        // 最新一条无条件纳入（与历史实现一致：预算极小时也带上最近一轮的上下文）
        for ((i, message) in recentMessages.asReversed().withIndex()) {
            val savedSnapshot =
                if (message.role == "user") promptCacheSnapshots[message.id].orEmpty() else emptyList()
            val replayedUserMessage = savedSnapshot.lastOrNull()?.takeIf { it.role == "user" }
            val replayedSuffix =
                if (replayedUserMessage != null) savedSnapshot.dropLast(1) else savedSnapshot
            val messageTokens =
                estimateTokens(replayedUserMessage?.content ?: message.content) + HISTORY_MESSAGE_OVERHEAD_TOKENS
            val snapshotTokens = replayedSuffix.sumOf { estimateTokens(it.content) + HISTORY_MESSAGE_OVERHEAD_TOKENS }
            val turnTokens = messageTokens + snapshotTokens
            if (i > 0 && usedTokens + turnTokens > historyBudget) {
                break
            }
            selected.addFirst(message)
            usedTokens += turnTokens
        }

        return selected.toList()
    }

    /**
     * @D 插入下标：从尾部回溯，使插入点之后恰好剩 `depth` 条**历史消息**（user/assistant）。
     *
     * 只数历史消息，不数夹在尾部的 system 块（记忆块 / 作者注释 / 世界书段）——
     * 这些块的增减不应改变 @D 的实际落点：此前记忆块在历史之前，如今移到历史之后，
     * 若仍按数组条数计数，@D 会无谓地提前（记忆块数）条。
     *
     * @param hasFinalUserMessage 后续还会追加一条当前用户消息，它计入「倒数第 1 条」，
     *                            故现有历史只需留 depth-1 条；群聊推动（无新用户消息）则留 depth 条
     *
     * internal 仅为单元测试开放：@D 落点对尾部系统块的免疫性需要回归测试
     */
    internal fun atDepthIndex(
        messages: List<ChatMessage>,
        depth: Int,
        hasFinalUserMessage: Boolean,
    ): Int {
        var chatRemaining = if (hasFinalUserMessage) depth - 1 else depth
        var index = messages.size
        while (index > 0 && chatRemaining > 0) {
            index--
            if (messages[index].role == "user" || messages[index].role == "assistant") chatRemaining--
        }
        return index
    }

    /**
     * 长期记忆上下文 → 待注入的 system 消息列表（顺序：结构化召回 → 向量/词法召回）。
     *
     * **注入位置约定**：调用方必须在历史之后、最终用户消息之前插入。召回集合由当前用户消息决定、
     * 逐轮都变；若排在历史之前会打断「角色卡 + 历史」这段逐字节稳定的前缀，
     * 使前缀缓存每轮全量未命中（这就是"比酒馆更消耗"的结构性原因之一）。
     *
     * 检索失败不阻塞对话（原样保留：异常吞掉、返回已构建的部分）。
     *
     * 长期记忆两块内容（**分开持有**，便于按会话是否被裁剪决定是否注入召回块）。
     *
     * - [structuredBlock]：结构化召回的事实（"## Known Information"）——绝不与历史重复，始终注入
     * - [recallBlock]：向量/词法召回的**本会话历史片段**——会话未被裁剪时整段历史都在提示词里，
     *   这些片段必然重复，注入只是白烧 token 并拉低前缀缓存命中率
     */
    internal data class MemoryContext(
        val structuredBlock: ChatMessage? = null,
        val recallBlock: ChatMessage? = null,
    ) {
        /** 组装用：会话历史未裁剪时丢弃召回块（无信息损失） */
        fun messagesFor(historyTruncated: Boolean): List<ChatMessage> =
            listOfNotNull(structuredBlock, recallBlock.takeIf { historyTruncated })

        /** 预算用：一律按含召回块计算（偏保守，不会撑爆上下文） */
        fun allMessages(): List<ChatMessage> = listOfNotNull(structuredBlock, recallBlock)
    }

    private suspend fun buildMemoryContext(
        db: AppDatabase,
        ownerId: String,
        characterId: String,
        sessionId: String,
        userMessage: String,
    ): MemoryContext {
        var structuredBlock: ChatMessage? = null
        var recallBlock: ChatMessage? = null

        val memories =
            db.structuredMemoryDao()
                .getPromptCandidates(ownerId, characterId, MEMORY_CANDIDATE_LIMIT)
                .map { it.toDomain() }
                .filterNot(MemoryConflictResolver::isSuperseded)

        val recalledMemories = selectRelevantMemories(memories, userMessage)
        val recalledContents = recalledMemories.map { it.content }.filter { it.isNotBlank() }
        if (recalledMemories.isNotEmpty()) {
            val accessedAt = Instant.now().toString()
            recalledMemories.map { it.id }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.let {
                db.structuredMemoryDao().incrementAccessCount(it, accessedAt)
            }
            structuredBlock =
                ChatMessage(
                    role = "system",
                    content = "## Known Information\n${formatStructuredMemoryContext(recalledMemories)}",
                )
        }

        recallBlock =
            buildHybridRecallBlock(
                db,
                HybridRecallRequest(ownerId, characterId, sessionId, userMessage, recalledContents),
            )

        return MemoryContext(structuredBlock = structuredBlock, recallBlock = recallBlock)
    }

    private suspend fun buildHybridRecallBlock(
        db: AppDatabase,
        request: HybridRecallRequest,
    ): ChatMessage? {
        val ownerId = request.ownerId
        val characterId = request.characterId
        val sessionId = request.sessionId
        val userMessage = request.userMessage
        val alreadyInjected = request.alreadyInjected
        // Hybrid retrieval: semantic vectors and exact lexical history run together.
        // Reciprocal-rank fusion avoids assuming the two retrievers' scores share a scale.
        val topK = ChatSettingsResolver.memoryRecallTopK(db.settingsDao().getValue("memory_recall_top_k"))
        val threshold =
            ChatSettingsResolver.memorySimilarityThreshold(
                db.settingsDao().getValue("memory_similarity_threshold"),
            )
        val vectorMemoryService = TavernApplication.instance.container.vectorMemoryService
        val vectorResults =
            try {
                if (vectorMemoryService.available) {
                    vectorMemoryService.searchRelevantMemories(
                        query = userMessage,
                        ownerId = ownerId,
                        characterId = characterId,
                        sessionId = sessionId,
                        topK = topK * VECTOR_RECALL_MULTIPLIER,
                    ).filter { it.score >= threshold }
                } else {
                    emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PromptBuilder", "Semantic memory search failed (${e.javaClass.simpleName})")
                emptyList()
            }

        // Lexical search is not merely a fallback: it recovers exact names, quoted phrases,
        // and literal details that embeddings can rank poorly.
        val lexicalItems =
            try {
                LexicalMemoryService(TavernApplication.instance)
                    .searchRelevantHistory(ownerId, characterId, sessionId, userMessage, topK = topK)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PromptBuilder", "Lexical memory search failed (${e.javaClass.simpleName})")
                emptyList()
            }
        val hybridContext =
            buildHybridMemoryContextForPrompt(
                vectorResults = vectorResults,
                lexicalItems = lexicalItems,
                alreadyInjected = alreadyInjected,
                limit = topK,
            )
        return hybridContext.takeIf { it.isNotBlank() }?.let { context ->
            ChatMessage(role = "system", content = context)
        }
    }

    private fun selectRelevantMemories(
        memories: List<StructuredMemory>,
        userMessage: String,
    ): List<StructuredMemory> {
        if (memories.isEmpty()) return emptyList()

        val result = linkedMapOf<Long, StructuredMemory>()

        memories
            .filter { it.importance >= 8 }
            .sortedWith(memoryComparator())
            .take(5)
            .forEach { result[it.stableKey()] = it }

        memories
            .filter { it.stableKey() !in result }
            .filter { it.importance >= 6 && it.memoryType.lowercase() in profileMemoryTypes }
            .sortedWith(memoryComparator())
            .take(3)
            .forEach { result[it.stableKey()] = it }

        val keywordTokens = extractQueryTokens(userMessage)
        if (keywordTokens.isNotEmpty()) {
            memories
                .filter { it.stableKey() !in result }
                .mapNotNull { memory ->
                    val score = keywordMatchScore(memory, keywordTokens)
                    if (score > 0) memory to score else null
                }
                .sortedWith(
                    compareByDescending<Pair<StructuredMemory, Int>> { it.second }
                        .thenByDescending { it.first.importance }
                        .thenByDescending { it.first.updatedAt },
                )
                .take(3)
                .forEach { result[it.first.stableKey()] = it.first }
        }

        return result.values
            .sortedWith(memoryComparator())
            .take(10)
    }

    private fun formatStructuredMemoryContext(memories: List<StructuredMemory>): String {
        val groups =
            linkedMapOf(
                "用户信息" to setOf("character_info", "identity", "preference"),
                "关系" to setOf("relationship"),
                "重要事件" to setOf("event", "core", "goal"),
                "情绪与边界" to setOf("emotion"),
                "相关物品" to setOf("item"),
                "相关地点" to setOf("location"),
            )

        val usedKeys = mutableSetOf<Long>()
        val lines = mutableListOf<String>()

        groups.forEach { (label, types) ->
            val items = memories.filter { it.memoryType.lowercase() in types }
            if (items.isNotEmpty()) {
                lines.add("$label：")
                items.forEach { memory ->
                    usedKeys.add(memory.stableKey())
                    lines.add("  - ${memory.content}（重要度：${memory.importance}/10）")
                }
                lines.add("")
            }
        }

        val facts = memories.filter { it.stableKey() !in usedKeys }
        if (facts.isNotEmpty()) {
            lines.add("其他事实：")
            facts.forEach { memory ->
                lines.add("  - ${memory.content}（重要度：${memory.importance}/10）")
            }
        }

        return lines.joinToString("\n").trim()
    }

    private fun keywordMatchScore(
        memory: StructuredMemory,
        queryTokens: Set<String>,
    ): Int {
        val haystack =
            buildString {
                append(memory.title.orEmpty()).append(' ')
                append(memory.content).append(' ')
                append(memory.tags.joinToString(" ")).append(' ')
                append(memory.keywords.joinToString(" "))
            }.lowercase()

        var score = 0
        queryTokens.forEach { token ->
            if (token.length >= 2 && haystack.contains(token)) score += 1
        }

        memory.tags.forEach { tag ->
            val normalized = tag.lowercase().trim()
            if (normalized.length >= 2 && queryTokens.any { it.contains(normalized) || normalized.contains(it) }) {
                score += 2
            }
        }
        memory.keywords.forEach { keyword ->
            val normalized = keyword.lowercase().trim()
            if (normalized.length >= 2 && queryTokens.any { it.contains(normalized) || normalized.contains(it) }) {
                score += 2
            }
        }

        return score
    }

    private fun extractQueryTokens(text: String): Set<String> {
        val tokens = linkedSetOf<String>()
        Regex("""[\u4e00-\u9fff]{2,}|[a-z0-9_]{3,}""").findAll(text.lowercase()).forEach { match ->
            val value = match.value.trim()
            if (value.length in 2..24) tokens.add(value)
            if (value.any { it.isCjk() } && value.length > 4) {
                value.windowed(2).forEach { tokens.add(it) }
                value.windowed(3).forEach { tokens.add(it) }
            }
        }
        return tokens
    }

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var ascii = 0
        var nonAscii = 0
        text.forEach { char ->
            if (char.code <= 127) ascii++ else nonAscii++
        }
        return maxOf(1, kotlin.math.ceil(ascii / ASCII_CHARS_PER_TOKEN + nonAscii / CJK_CHARS_PER_TOKEN).toInt())
    }

    private fun memoryComparator(): Comparator<StructuredMemory> =
        compareByDescending<StructuredMemory> { MemoryRanking.score(it) }
            .thenByDescending { it.importance }
            .thenByDescending { it.accessCount }
            .thenByDescending { it.updatedAt }

    private fun StructuredMemory.stableKey(): Long = if (id > 0) id else content.hashCode().toLong()

    private fun Char.isCjk(): Boolean = this in '\u4e00'..'\u9fff'

    private val profileMemoryTypes =
        setOf(
            "character_info",
            "identity",
            "preference",
            "relationship",
        )

    /**
     * Fuse semantic and lexical retrieval using Reciprocal Rank Fusion (RRF).
     * Candidate scores are ranks, not raw similarity values, so unlike score addition
     * this remains stable when providers use different similarity scales.
     */
    internal fun buildHybridMemoryContextForPrompt(
        vectorResults: List<VectorStore.SearchResult>,
        lexicalItems: List<MessageEntity>,
        alreadyInjected: List<String>,
        limit: Int = 8,
    ): String =
        if (limit <= 0) {
            ""
        } else {
            val candidates = linkedMapOf<String, HybridCandidate>()

            fun add(
                content: String,
                rank: Int,
                source: String,
                dedupeContent: String = content,
            ) {
                val trimmed = content.trim()
                val canonicalContent = normalizeHybridDedupeContent(dedupeContent)
                if (trimmed.isBlank() || isDuplicateOfInjected(dedupeContent, alreadyInjected)) return
                if (canonicalContent.isBlank()) return
                val contribution = 1.0 / (RRF_RANK_CONSTANT + rank)
                val candidate = candidates[canonicalContent]
                if (candidate == null) {
                    candidates[canonicalContent] = HybridCandidate(trimmed, contribution, linkedSetOf(source))
                } else {
                    candidate.score += contribution
                    candidate.sources.add(source)
                }
            }

            vectorResults
                .filter { it.score > MIN_HYBRID_VECTOR_SCORE }
                .forEachIndexed { index, result -> add(result.content, index + 1, "语义") }
            lexicalItems.forEachIndexed { index, message ->
                val stamp = message.createdAt.take(HISTORY_TIMESTAMP_PREFIX_LENGTH).replace('T', ' ')
                add(
                    content = "[$stamp] ${message.role}: ${message.content.take(MAX_LEXICAL_CONTENT_CHARS)}",
                    rank = index + 1,
                    source = "关键词",
                    dedupeContent = message.content,
                )
            }

            val ranked = candidates.values.sortedByDescending { it.score }.take(limit)
            if (ranked.isEmpty()) {
                ""
            } else {
                buildString {
                    appendLine("## Relevant Past Conversations")
                    ranked.forEachIndexed { index, candidate ->
                        appendLine("${index + 1}. [${candidate.sources.joinToString("+")}] ${candidate.content}")
                    }
                }.trim()
            }
        }

    private fun normalizeHybridDedupeContent(content: String): String =
        content.trim()
            .replace(Regex("""^\[[^]]+\]\s*"""), "")
            .replace(Regex("""^(user|assistant|system|角色|用户):\s*""", RegexOption.IGNORE_CASE), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    /**
     * 构建向量记忆上下文（用于 Prompt 注入）。
     *
     * @param alreadyInjected 本提示词中已注入的结构化记忆正文——向量库里那些「重要度≥7 记忆的 summary 副本」
     *                        内容即为其正文，若再注入就是同一事实烧两遍 token，故按内容覆盖关系丢弃
     *
     * internal 仅为单元测试开放：重复注入的过滤规则需要回归测试
     */
    internal fun buildVectorMemoryContextForPrompt(
        results: List<VectorStore.SearchResult>,
        alreadyInjected: List<String>,
    ): String {
        val relevantResults =
            results
                .filter { it.score > 0.5 }
                .filterNot { result -> isDuplicateOfInjected(result.content, alreadyInjected) }
        if (relevantResults.isEmpty()) return ""

        return buildString {
            appendLine("## Relevant Past Conversations")
            relevantResults.forEachIndexed { index, result ->
                val similarityPercent = (result.score * 100).toInt()
                appendLine("${index + 1}. ${result.content} (similarity: $similarityPercent%)")
            }
        }
    }

    /**
     * 向量召回行是否已被注入的记忆正文覆盖（双向包含，短文本不参与判定以免误伤）。
     * summary 副本的形态是 "标题: 正文 [类型]"，故「向量行包含记忆正文」即为重复。
     */
    private fun isDuplicateOfInjected(
        content: String,
        alreadyInjected: List<String>,
    ): Boolean {
        if (content.length < MIN_DUPLICATE_LENGTH) return false
        return alreadyInjected.any { injected ->
            injected.length >= MIN_DUPLICATE_LENGTH &&
                (content.contains(injected) || injected.contains(content))
        }
    }

    /**
     * 组内条目渲染为一条 system 消息文本（World Info 常驻段 + Activated World Info 激活段）；
     * 组内无有效内容（空组或非常驻条目均未激活）返回 null，不产生空消息
     *
     * @param constantOnly true=仅渲染 constant 条目（用于历史前注入），false=仅渲染激活条目（用于历史后注入）
     */
    private fun worldInfoBlock(
        group: List<WorldBookEntryEntity>,
        activatedIds: Set<String>,
        mctx: MacroContext,
        constantOnly: Boolean = false,
    ): String? {
        if (constantOnly) {
            // 仅渲染 constant 条目（历史前注入，保持前缀稳定）
            return renderWorldInfoSection("World Info:", group.filter { it.constant }, mctx)
        } else {
            // 仅渲染激活条目（历史后注入，易变部分）
            return renderWorldInfoSection(
                "Activated World Info:",
                group.filter { !it.constant && "${it.bookId}:${it.id}" in activatedIds },
                mctx,
            )
        }
    }

    /** 单段渲染：标题 + 逐条 "[comment] 宏渲染内容"；空列表返回 null */
    private fun renderWorldInfoSection(
        title: String,
        entries: List<WorldBookEntryEntity>,
        mctx: MacroContext,
    ): String? =
        entries.takeIf { it.isNotEmpty() }?.let {
            "$title\n" +
                it.joinToString("\n\n") { e -> "[${e.comment}] ${MacroEngine.render(e.content, mctx)}" }
        }
}
