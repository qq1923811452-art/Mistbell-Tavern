package com.mistbell.tavern.android.service

import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ChatMessage
import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.model.StructuredMemory
import com.mistbell.tavern.android.data.repository.StructuredMemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.math.min

class MemoryExtractionService(
    private val context: Context,
    private val structuredMemoryRepository: StructuredMemoryRepository,
) {
    private val db = TavernApplication.instance.container.database

    companion object {
        private const val TAG = "MemoryExtraction"
        private const val MIN_DIALOGUE_LENGTH = 30
        private const val MIN_MEMORY_CONTENT_LENGTH = 10
        private const val MIN_IMPORTANCE_SCORE = 0.5 // 降低阈值，让更多内容有机会被保存
        private const val SIMILARITY_THRESHOLD = 0.85
        private const val MAX_HISTORICAL_MEMORY_IMPORTANCE = 7

        /** 空正文重试时追加：部分模型会把输出预算全花在思考过程上，导致 JSON 正文为空 */
        private const val NO_REASONING_SUFFIX =
            "\n\n注意：直接输出上述 JSON，不要输出任何思考、分析或解释过程。"
    }

    /** 一轮待抽取对话：群聊"让TA继续"等场景没有用户消息，userMessage 为空串 */
    data class DialogueTurn(
        val userMessage: String,
        val assistantMessage: String,
        val messageIds: List<String>,
    )

    // 多轮合并抽取：ChatRepository 攒满若干轮后一次性传入，摊薄每轮一次 LLM 调用的固定开销
    suspend fun extractAndSaveMemories(
        turns: List<DialogueTurn>,
        ownerId: String,
        characterId: String,
        sessionId: String,
        config: LlmConfig?,
    ): Int =
        withContext(Dispatchers.IO) {
            val dialogueText = buildDialogueText(turns)
            val messageIds = turns.flatMap { it.messageIds }.distinct()

            Log.d(TAG, "Extraction start: session=$sessionId, character=$characterId, turns=${turns.size}")
            if (!shouldExtractMemory(dialogueText)) {
                Log.d(TAG, "Skipped by quick quality check")
                return@withContext 0
            }

            if (config == null) {
                Log.w(TAG, "No LLM config available, skipping LLM extraction")
                return@withContext 0
            }

            try {
                val candidates = extractMemoryCandidatesWithLLM(dialogueText, config)
                if (candidates.isEmpty()) {
                    Log.d(TAG, "No memory candidates returned")
                    return@withContext 0
                }

                val validated = validateAndEnhanceCandidates(candidates, ownerId, characterId)
                if (validated.isEmpty()) {
                    Log.d(TAG, "No candidates survived quality validation")
                    return@withContext 0
                }

                var savedCount = 0
                validated.forEach { candidate ->
                    try {
                        val memory =
                            convertToMemoryDomain(
                                candidate = candidate,
                                ownerId = ownerId,
                                characterId = characterId,
                                sessionId = sessionId,
                                messageIds = messageIds,
                            )
                        val newMemoryId = structuredMemoryRepository.createMemory(memory)
                        markSupersededMemoriesAsHistorical(candidate.supersededMemoryIds, newMemoryId)
                        savedCount++
                        Log.d(TAG, "Saved memory candidate #$savedCount")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save memory: ${e.message}", e)
                    }
                }

                Log.d(
                    TAG,
                    "Extraction complete: ${candidates.size} candidates, ${validated.size} valid, $savedCount saved",
                )
                savedCount
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Memory extraction failed: ${e.message}", e)
                0
            }
        }

    private suspend fun markSupersededMemoriesAsHistorical(
        oldMemoryIds: List<Long>,
        newMemoryId: Long,
    ) {
        oldMemoryIds.forEach { oldMemoryId ->
            markSingleMemoryAsHistorical(oldMemoryId, newMemoryId)
        }
    }

    private suspend fun markSingleMemoryAsHistorical(
        oldMemoryId: Long,
        newMemoryId: Long,
    ) {
        try {
            val oldMemory = structuredMemoryRepository.getMemoryById(oldMemoryId) ?: return
            val historicalMemory =
                oldMemory.copy(
                    content =
                        if (oldMemory.content.startsWith("【历史事实】")) {
                            oldMemory.content
                        } else {
                            "【历史事实】${oldMemory.content}"
                        },
                    structuredData =
                        TemporalMemoryFactPolicy.markHistorical(
                            oldMemory.structuredData,
                            Instant.now().toString(),
                            newMemoryId,
                        ),
                    tags = (listOf("历史事实") + oldMemory.tags).distinct().take(8),
                    keywords = (listOf("历史事实") + oldMemory.keywords).distinct().take(8),
                    importance = min(oldMemory.importance, MAX_HISTORICAL_MEMORY_IMPORTANCE),
                )
            structuredMemoryRepository.updateMemory(historicalMemory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark superseded memory as historical: ${e.message}", e)
        }
    }

    // 多轮拼接：轮间空行分隔；无用户消息的轮次（群聊"让TA继续"）省略 User 行
    private fun buildDialogueText(turns: List<DialogueTurn>): String {
        return turns.joinToString("\n\n") { turn ->
            if (turn.userMessage.isBlank()) {
                "Assistant: ${turn.assistantMessage}"
            } else {
                "User: ${turn.userMessage}\nAssistant: ${turn.assistantMessage}"
            }
        }
    }

    private fun shouldExtractMemory(message: String): Boolean {
        val trimmed = message.trim()
        if (trimmed.length < MIN_DIALOGUE_LENGTH) return false

        val userLine =
            trimmed
                .lineSequence()
                .firstOrNull { it.startsWith("User:") }
                ?.removePrefix("User:")
                ?.trim()
                .orEmpty()

        if (userLine.isNotBlank() && lowQualityPatterns.any { it.matches(userLine) }) {
            return false
        }

        val contentChars = trimmed.count { it.isLetterOrDigit() || it.isCjk() }
        val contentRatio = contentChars.toDouble() / trimmed.length
        return contentRatio >= 0.3
    }

    private suspend fun extractMemoryCandidatesWithLLM(
        dialogueText: String,
        llmConfig: LlmConfig,
    ): List<MemoryCandidate> {
        val prompt = buildMemoryExtractionPrompt(dialogueText)
        val config =
            llmConfig.copy(
                // 抽取温度低（结构化任务）；输出预算必须容纳「最多 10 条 triplet + tags/aliases」
                // 的完整 JSON：过小的预算会让 JSON 写到一半被截断，解析失败后整轮 0 条（静默丢记忆）
                temperature = 0.2,
                maxTokens = 4096,
                // 关闭思考模式（DeepSeek OpenAI 格式 {"thinking":{"type":"disabled"}}）：
                // 实测抽取单轮 4K 输出里约 3.5K 是思维链，且思考还会吃光预算导致正文为空；
                // 抽取是纯结构化任务，关掉思考 = 省输出 token + 根治空正文重试。
                // 网关不认该参数时 LlmClient 自动回退为不发送（见 chat()）
                disableThinking = true,
            )

        val response = requestExtraction(config, prompt)

        // 诊断只记结构特征（长度/形状），不记响应正文——响应派生自用户聊天内容（隐私）
        if (response.isBlank()) {
            Log.w(TAG, "LLM returned blank content (empty/truncated-at-budget response)")
            return emptyList()
        }
        val parsed = parseMemoryExtractionResult(response)
        Log.d(
            TAG,
            "LLM response diag: len=${response.length}, triplets=${parsed.size}, " +
                "startsWithBrace=${response.trimStart().startsWith('{')}, " +
                "endsWithBrace=${response.trimEnd().endsWith('}')}, " +
                "codeFenced=${response.contains("```")}",
        )
        return parsed
    }

    /**
     * 发起抽取请求。首次返回**空正文**（输出预算被思考过程吃光的典型形态：finish_reason=length
     * 且 content 为空）时重试一次，并在提示词后追加"直接输出 JSON"的压制语——
     * 否则该轮记忆整轮丢失，且用户侧完全无感。
     */
    private suspend fun requestExtraction(
        config: LlmConfig,
        prompt: String,
    ): String {
        val first = LlmClient.chat(config, listOf(ChatMessage(role = "user", content = prompt)))
        if (first.isNotBlank()) return first

        Log.w(TAG, "Blank content on first attempt; retrying with no-reasoning instruction")
        return try {
            LlmClient.chat(
                config,
                listOf(ChatMessage(role = "user", content = prompt + NO_REASONING_SUFFIX)),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Retry after blank content failed: ${e.message}")
            first
        }
    }

    private suspend fun buildMemoryExtractionPrompt(dialogueText: String): String {
        val customPrompt =
            withContext(Dispatchers.IO) {
                db.settingsDao().getValue("memory_extraction_prompt")
            }
        val template =
            customPrompt
                ?.takeIf { it.contains("%s") }
                ?.takeUnless { looksMojibake(it) }
                ?: getDefaultPrompt()

        return template.replace("%s", dialogueText)
    }

    private fun getDefaultPrompt(): String {
        return """
            你是对话记忆提取器。分析下方对话，提取值得长期保存的事实。

            **输出纯 JSON（无 Markdown、无解释）：**
            {
              "triplets": [
                {
                  "subject": "实体名（user/角色/地点/物品/组织）",
                  "relation": "关系类型",
                  "object": "取值",
                  "memoryType": "类型",
                  "importance": 0.0,
                  "tags": ["2-6个关键词"],
                  "aliases": ["0-4个别名/同义说法"],
                  "rawText": "10-80字第三人称陈述"
                }
              ]
            }

            **relation 可选值：**
            name, likes, dislikes, prefers, wants, boundary, afraid_of, promised, located_at, member_of, role_is, has_item, title_is, told_user, confirmed, other

            **memoryType 可选值：**
            fact, event, emotion, core, preference, identity, relationship, goal, note, character_info, item, location

            **提取规则：**
            1. **范围**：身份、稳定偏好、长期边界、关系、目标、承诺、已确认设定、有持续影响的事件
            2. **忽略**：临时情绪、空泛承诺、纯状态栏（HP/坐标/姿势）、礼貌用语、复述
            3. **格式**：
               - rawText 用第三人称陈述，主语 user 保留英文
               - 一条记忆一个原子事实，不要合并
               - 方括号内的设定信息要提取（地名/身份/境界/职位）
            4. **特殊处理**：
               - 角色"告诉"user 某事 → relation 用 told_user
               - user 自述或已确认 → 视为 user 属性
               - 事件有时间线索（明天/上周）→ 写进 rawText
               - 与已有记忆冲突 → rawText 显式写"已改为"
            5. **数量**：整段最多 10 条，优先 importance ≥ 0.6

            **importance 标尺：**
            - 0.85-1.0：重大创伤、生死、誓言、核心身份
            - 0.7-0.85：身份、长期边界、明确目标、关键承诺
            - 0.5-0.7：稳定偏好、重要关系、项目状态、持续影响的事件
            - 0.35-0.5：一般背景、一次性事件、世界设定细节

            **示例：**
            "我叫墨轩" → {"subject":"user","relation":"name","object":"墨轩","memoryType":"identity","importance":0.9,"tags":["名字","身份"],"aliases":["墨轩","名字"],"rawText":"user 的名字是墨轩"}

            "我不喜欢被叫主人" → {"subject":"user","relation":"boundary","object":"不喜欢被叫主人","memoryType":"preference","importance":0.8,"tags":["称呼","边界"],"aliases":["主人","称呼偏好"],"rawText":"user 不喜欢被叫主人"}

            "[凌月璃♀人族-玉臀宗长老-元婴]" → {"subject":"凌月璃","relation":"member_of","object":"玉臀宗","memoryType":"character_info","importance":0.75,"tags":["玉臀宗","长老","元婴"],"aliases":["凌月璃","玉臀宗长老"],"rawText":"凌月璃是玉臀宗长老，元婴期修为"}

            "艾琳说：我欠你一次人情" → {"subject":"艾琳","relation":"told_user","object":"欠 user 一次人情","memoryType":"relationship","importance":0.7,"tags":["人情","承诺"],"aliases":["欠人情"],"rawText":"艾琳说欠 user 一次人情"}

            无可提取内容返回 {"triplets": []}

            **对话片段：**
            %s
            """.trimIndent()
    }

    private fun parseMemoryExtractionResult(rawJson: String): List<MemoryCandidate> {
        val cleanJson = MemoryExtractionJson.cleanup(rawJson)
        parseCandidates(cleanJson)?.let { return it }

        // 截断抢救：响应被 max_tokens 砍断时（部分网关输出上限约 1K token，提预算也无效），
        // 截断点之前仍是合法 JSON 前缀——剪到最后一个完整的 triplet 对象再解析一次，
        // 保住已完成的条目，而不是整轮丢光（此前每次截断都静默产出 0 条）
        val salvaged = MemoryExtractionJson.salvageTruncated(cleanJson) ?: return emptyList()
        val result = parseCandidates(salvaged)
        if (result != null) {
            Log.w(TAG, "Response truncated by output limit; salvaged ${result.size} complete candidates")
        }
        return result ?: emptyList()
    }

    /** 解析 JSON 为候选列表；解析失败返回 null（与"解析成功但无候选"区分，供截断抢救判断） */
    private fun parseCandidates(cleanJson: String): List<MemoryCandidate>? {
        return try {
            val root = Json.parseToJsonElement(cleanJson)
            val triplets =
                when (root) {
                    // 直接是数组
                    is kotlinx.serialization.json.JsonArray -> root
                    // triplets 不是数组（缺键/为 null/被写成对象）时按"无候选"处理，不抛异常
                    is kotlinx.serialization.json.JsonObject ->
                        root["triplets"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
                    else -> return emptyList()
                }
            triplets.mapNotNull { triplet ->
                runCatching {
                    val obj = triplet.jsonObject
                    val subject = obj["subject"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val relation = obj["relation"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val objectValue = obj["object"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val rawText = obj["rawText"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val content = rawText.ifBlank { buildContentFromParts(subject, relation, objectValue) }
                    val memoryType =
                        normalizeMemoryType(
                            obj["memoryType"]?.jsonPrimitive?.contentOrNull
                                ?: obj["type"]?.jsonPrimitive?.contentOrNull
                                ?: "fact",
                        )

                    MemoryCandidate(
                        subject = subject,
                        relation = relation,
                        objValue = objectValue,
                        content = content,
                        memoryType = memoryType,
                        importance = obj["importance"]?.jsonPrimitive?.doubleOrNull ?: 0.5,
                        tags =
                            obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                                ?.filter { it.isNotBlank() }
                                ?: emptyList(),
                        keywords =
                            buildList {
                                add(subject)
                                add(relation)
                                add(objectValue)
                                obj["aliases"]?.jsonArray?.forEach { alias ->
                                    alias.jsonPrimitive.contentOrNull?.trim()?.let { add(it) }
                                }
                            }.filter { it.isNotBlank() }.distinct(),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            // 注意：不主动记录 rawJson 正文，避免 LLM 响应（含聊天上下文）进入日志
            Log.e(TAG, "Failed to parse memory extraction result: ${e.message}")
            null
        }
    }

    private suspend fun validateAndEnhanceCandidates(
        candidates: List<MemoryCandidate>,
        ownerId: String,
        characterId: String,
    ): List<MemoryCandidate> {
        val existingMemories =
            structuredMemoryRepository
                .getMemoriesByCharacter(ownerId, characterId)
                .first()
        val existingFacts =
            existingMemories.mapNotNull { memory ->
                TemporalMemoryFactPolicy.parseFact(memory.id, memory.structuredData)
            }
        val validated = mutableListOf<MemoryCandidate>()

        candidates.forEach { candidate ->
            val normalized =
                candidate.copy(
                    content = sanitizeMemoryContent(candidate.content),
                    memoryType = normalizeMemoryType(candidate.memoryType),
                )
            if (!isUsableMemoryContent(normalized.content)) return@forEach

            val enhanced =
                enhanceImportance(normalized)
                    .copy(keywords = enrichKeywords(normalized).distinct().take(8))
            if (enhanced.importance < MIN_IMPORTANCE_SCORE) return@forEach

            val relatedFacts =
                TemporalMemoryFactPolicy.findRelatedFacts(
                    subject = enhanced.subject,
                    relation = enhanced.relation,
                    objectValue = enhanced.objValue,
                    existingFacts = existingFacts,
                )
            val supersededFacts =
                TemporalMemoryFactPolicy.findConflicts(
                    subject = enhanced.subject,
                    relation = enhanced.relation,
                    objectValue = enhanced.objValue,
                    content = enhanced.content,
                    existingFacts = existingFacts,
                )
            val ignoredDuplicateIds = relatedFacts.map { it.id }.toSet()
            if (isDuplicate(enhanced, existingMemories, ignoredDuplicateIds)) return@forEach

            validated +=
                enhanced.copy(
                    supersededMemoryIds = supersededFacts.map { it.id },
                    previousValues = supersededFacts.map { it.objectValue },
                )
        }
        return validated.distinctBy { normalizeForCompare(it.content) }
    }

    private fun convertToMemoryDomain(
        candidate: MemoryCandidate,
        ownerId: String,
        characterId: String,
        sessionId: String,
        messageIds: List<String>,
    ): StructuredMemory {
        val now = Instant.now().toString()
        // 优先使用自然语言描述作为标题，避免机械拼接的不自然感
        val title =
            candidate.content.take(50).ifBlank {
                generateTitle(candidate.subject, candidate.relation, candidate.objValue)
            }
        val importanceScore = (candidate.importance * 10).toInt().coerceIn(1, 10)

        return StructuredMemory(
            id = 0,
            ownerId = ownerId,
            characterId = characterId,
            sessionId = sessionId,
            title = title,
            content = candidate.content,
            memoryType = candidate.memoryType,
            importance = importanceScore,
            tags = candidate.tags.take(8),
            keywords = candidate.keywords.take(8),
            structuredData =
                TemporalMemoryFactPolicy.markCurrent(
                    structuredData =
                        Json.encodeToString(
                            mapOf(
                                "subject" to candidate.subject,
                                "relation" to candidate.relation,
                                "object" to candidate.objValue,
                            ),
                        ),
                    validFrom = now,
                    supersededIds = candidate.supersededMemoryIds,
                    previousValues = candidate.previousValues,
                ),
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now,
            accessCount = 0,
            relatedMessageIds = messageIds,
            sourceType = "auto_extract",
        )
    }

    private fun isUsableMemoryContent(content: String): Boolean {
        if (content.length < MIN_MEMORY_CONTENT_LENGTH || content.length > 160) return false
        if (content.contains("User:", ignoreCase = true) || content.contains("Assistant:", ignoreCase = true)) {
            return false
        }
        if (lowQualityPatterns.any { it.matches(content.trim()) }) return false

        val contentChars = content.count { it.isLetterOrDigit() || it.isCjk() }
        if (contentChars.toDouble() / content.length < 0.4) return false

        // 移除过严的英文内容过滤，让LLM决定内容是否值得保存
        return true
    }

    private fun sanitizeMemoryContent(content: String): String {
        return content
            .replace(Regex("\\s+"), " ")
            .replace("（（", "（")
            .replace("））", "）")
            .trim()
            .trim('。', '.', ',', '，', ';', '；')
    }

    private fun enhanceImportance(candidate: MemoryCandidate): MemoryCandidate {
        val content = candidate.content.lowercase()
        var importance = candidate.importance.coerceIn(0.0, 1.0)

        if (highValueKeywords.any { content.contains(it.lowercase()) }) {
            importance = min(1.0, importance + 0.1)
        }
        if (healthKeywords.any { content.contains(it.lowercase()) }) {
            importance = min(1.0, importance + 0.15)
        }
        if (candidate.memoryType in identityLikeTypes) {
            importance = min(1.0, importance + 0.08)
        }

        return candidate.copy(importance = importance)
    }

    private fun enrichKeywords(candidate: MemoryCandidate): List<String> {
        val generated =
            extractTokens(
                "${candidate.subject} ${candidate.relation} ${candidate.objValue} ${candidate.content}",
            )
        return candidate.keywords + candidate.tags + generated
    }

    private fun isDuplicate(
        candidate: MemoryCandidate,
        existing: List<StructuredMemory>,
        ignoredMemoryIds: Set<Long> = emptySet(),
    ): Boolean {
        val candidateContent = normalizeForCompare(candidate.content)
        if (candidateContent.isBlank()) return true

        return existing.any { memory ->
            if (memory.id in ignoredMemoryIds || TemporalMemoryFactPolicy.isHistorical(memory.structuredData)) {
                return@any false
            }
            val existingContent = normalizeForCompare(memory.content)
            if (existingContent.isBlank()) return@any false
            if (
                candidateContent.length >= 8 &&
                (candidateContent.contains(existingContent) || existingContent.contains(candidateContent))
            ) {
                return@any true
            }
            calculateSimilarity(candidateContent, existingContent) > SIMILARITY_THRESHOLD
        }
    }

    private fun calculateSimilarity(
        text1: String,
        text2: String,
    ): Double {
        val words1 = tokenSetForSimilarity(text1)
        val words2 = tokenSetForSimilarity(text2)
        if (words1.isEmpty() || words2.isEmpty()) return 0.0

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun tokenSetForSimilarity(text: String): Set<String> {
        val tokens = linkedSetOf<String>()
        Regex("""[\u4e00-\u9fff]+|[a-z0-9_]+""").findAll(text.lowercase()).forEach { match ->
            val value = match.value
            if (value.any { it.isCjk() }) {
                if (value.length <= 3) {
                    tokens.add(value)
                } else {
                    value.windowed(2).forEach { tokens.add(it) }
                    value.windowed(3).forEach { tokens.add(it) }
                }
            } else if (value.length >= 3) {
                tokens.add(value)
            }
        }
        return tokens
    }

    private fun extractTokens(text: String): List<String> {
        return tokenSetForSimilarity(text)
            .filter { it.length in 2..16 }
            .take(12)
    }

    private fun normalizeForCompare(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]+"), "")
            .trim()
    }

    private fun normalizeMemoryType(rawType: String): String {
        val type = rawType.trim().lowercase()
        return when (type) {
            "character_info", "identity", "preference", "relationship", "event",
            "core", "goal", "emotion", "item", "location", "fact", "note",
            -> type
            "profile", "user_info", "userinfo" -> "character_info"
            "boundary", "like", "dislike" -> "preference"
            "place" -> "location"
            "object" -> "item"
            else -> "fact"
        }
    }

    private fun buildContentFromParts(
        subject: String,
        relation: String,
        obj: String,
    ): String {
        return listOf(subject, relation, obj)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    private fun generateTitle(
        subject: String,
        relation: String,
        obj: String,
    ): String {
        return when {
            subject.isNotBlank() && relation.isNotBlank() && obj.isNotBlank() -> "$subject $relation $obj"
            subject.isNotBlank() && relation.isNotBlank() -> "$subject $relation"
            subject.isNotBlank() && obj.isNotBlank() -> "$subject - $obj"
            else -> subject.ifBlank { "记忆" }
        }.take(100)
    }

    private fun looksMojibake(text: String): Boolean {
        val signals = listOf("浣犳", "璁板繂", "鎻愬彇", "涓嶈", "鍙", "鈥", "�")
        return signals.count { text.contains(it) } >= 2
    }

    private fun containsChineseSignal(text: String): Boolean {
        return text.any { it.isCjk() }
    }

    private fun Char.isCjk(): Boolean = this in '\u4e00'..'\u9fff'

    private val lowQualityPatterns =
        listOf(
            Regex("""^(你好|谢谢|再见|好的|好|嗯+|哦+|啊+|哈哈+|笑+)[。！？!.?,，]*$""", RegexOption.IGNORE_CASE),
            Regex("""^(hi|hello|thanks|bye|ok|okay|yeah|nope|yes|no)[。！？!.?,，]*$""", RegexOption.IGNORE_CASE),
            Regex("""^[\p{Punct}\s，。！？、；：“”‘’（）【】《》]+$"""),
            Regex("""^(哈|哈哈|hhh|lol|lmao)+$""", RegexOption.IGNORE_CASE),
        )

    private val highValueKeywords =
        setOf(
            "过敏", "禁忌", "不能", "必须", "重要", "拒绝", "讨厌", "喜欢",
            "名字", "叫", "家人", "父母", "孩子", "家庭", "目标", "计划",
            "承诺", "边界", "不会", "不想", "allergic", "allergy", "cannot",
            "must", "important", "family", "parent", "child", "children",
        )

    private val healthKeywords =
        setOf(
            "过敏", "疾病", "生病", "药", "医院", "健康", "创伤", "allergic", "health", "trauma",
        )

    private val identityLikeTypes =
        setOf(
            "character_info",
            "identity",
            "preference",
            "relationship",
            "core",
            "goal",
        )

    data class MemoryCandidate(
        val subject: String,
        val relation: String,
        val objValue: String,
        val content: String,
        val memoryType: String,
        val importance: Double,
        val tags: List<String>,
        val keywords: List<String>,
        val supersededMemoryIds: List<Long> = emptyList(),
        val previousValues: List<String> = emptyList(),
    )
}

/**
 * 记忆抽取响应的 JSON 清洗与截断抢救（纯函数，无 Android 依赖，便于 JVM 单测）。
 *
 * 现实约束：部分网关/模型的**输出上限固定且偏小**——实测 deepseek-v4-flash 在请求
 * max_tokens=4096 的情况下仍于约 1K token 处返回 finish_reason=length。因此不能指望
 * "一次拿到完整 JSON"；截断点之前是合法 JSON 前缀，必须能抢救出已完成的条目，
 * 否则整轮静默产出 0 条（这正是长期记忆"从不入库"的根因）。
 */
internal object MemoryExtractionJson {
    /** 剥代码围栏、剥离 JSON 前后的说明文字/推理残留、去掉尾随逗号 */
    fun cleanup(rawJson: String): String {
        var s = rawJson.trim()
        if (s.startsWith("```json")) s = s.removePrefix("```json").trim()
        if (s.startsWith("```")) s = s.removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()

        // 夹带说明文字（或推理残留）时截取 JSON 主体：首个容器字符 → 末个同级收口字符
        val containerStart = listOf(s.indexOf('{'), s.indexOf('[')).filter { it >= 0 }.minOrNull()
        if (containerStart != null && containerStart > 0) s = s.substring(containerStart)
        val closing =
            if (s.startsWith("{")) {
                '}'
            } else if (s.startsWith("[")) {
                ']'
            } else {
                null
            }
        if (closing != null) {
            val end = s.lastIndexOf(closing)
            if (end >= 0 && end < s.length - 1) s = s.substring(0, end + 1)
        }

        s = s.replace(Regex(",\\s*\\}"), "}")
        s = s.replace(Regex(",\\s*\\]"), "]")
        return s
    }

    /**
     * 截断抢救：剪到最后一个完整的 triplet 对象并补齐收口括号。
     * 无法抢救（结构不是 triplet 数组 / 没有可收口的完整对象）时返回 null。
     */
    fun salvageTruncated(cleanJson: String): String? {
        val arrayStart = cleanJson.indexOf('[')
        if (arrayStart < 0) return null
        val lastObjectEnd = cleanJson.lastIndexOf('}')
        if (lastObjectEnd <= arrayStart) return null
        // 数组外层是否有 triplets 包装，决定补 "]" 还是 "]}"
        val wrapped = cleanJson.lastIndexOf("\"triplets\"", arrayStart) >= 0
        return cleanJson.substring(0, lastObjectEnd + 1) + if (wrapped) "]}" else "]"
    }
}
