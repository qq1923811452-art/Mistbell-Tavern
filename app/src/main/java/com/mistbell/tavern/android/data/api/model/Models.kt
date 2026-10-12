package com.mistbell.tavern.android.data.api.model

import androidx.compose.runtime.Immutable
import com.mistbell.tavern.android.data.api.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class StateResponse(
    val characters: List<Character> = emptyList(),
    @SerialName("activeSessionId") val activeSessionId: String = "",
    val sessions: List<SessionSummary> = emptyList(),
    @SerialName("recentSessions") val recentSessions: List<SessionSummary> = emptyList(),
    val conversation: List<Message> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val worldBook: WorldBook? = null,
)

@Immutable
@Serializable
data class Character(
    val id: String = "",
    val name: String = "",
    val role: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    val color: String = "",
    @SerialName("avatarData") val avatarData: String = "",
    @SerialName("worldBookId") val worldBookId: String = "",
    @SerialName("themeId") val themeId: String = "",
    val data: CharacterData? = null,
)

@Serializable
data class CharacterData(
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "1.0",
    // 备用开场白（SillyTavern v2 规范字段 alternate_greetings），默认空列表保证旧 JSON 兼容
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    // 标签列表
    val tags: List<String> = emptyList(),
    // 生态扩展命名空间：原样透传保真，不做字段展开
    val extensions: JsonObject? = null,
    // v3 规范新增字段：默认值保证 v2/v1 旧 JSON 兼容；多语言备注/来源/资源原样透传保真
    val nickname: String = "",
    @SerialName("creator_notes_multilingual") val creatorNotesMultilingual: JsonElement? = null,
    val source: JsonElement? = null,
    @SerialName("group_only_greetings") val groupOnlyGreetings: List<String> = emptyList(),
    val assets: JsonElement? = null,
)

// ---- 会话模式常量（v17 模式骨架，MODES.md）----
// 已实现取值：classic | group；"roleplay"（扮演，MODES 模式②）与后续④⑤档为骨架预留
// （存储层一次表达全部五档，未来加模式零迁移）。
// 用户侧命名：经典 / 扮演 / 群聊 / 导演 / 卡对卡剧场（docs/MODES.md 总表）。
// 「扮演」本批仅创建页占位（即将推出，不可创建），实装时引用本常量接入 PromptBuilder 叙事者链路。
// 两侧代理（数据层/UI）必须引用同一份常量，禁止手写字符串字面量。
const val SESSION_MODE_CLASSIC = "classic"
const val SESSION_MODE_GROUP = "group"
// 模式②「扮演」骨架常量：实装前创建页仅作占位展示，禁止以此值建会话（无对应提示词链路）
const val SESSION_MODE_ROLEPLAY = "roleplay"

// 群聊上下文（跨代理契约 3，数据层定义、UI 层只读引用）：
// speakerNames —— 参与者 id→名字（含主角色）；targetSpeakerId —— 用户 @提及 解析出的目标角色 id，空 = 无指定
data class GroupChatContext(
    val speakerNames: Map<String, String>,
    val targetSpeakerId: String? = null,
)

// 群聊说话方解析 parseGroupSpeaker（util/GroupSpeaker.kt）的命中结果
data class GroupSpeakerResult(
    val speakerId: String,
    val strippedContent: String,
)

@Serializable
data class SessionSummary(
    val id: String = "",
    val title: String = "",
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("updatedAt") val updatedAt: String = "",
    @SerialName("messageCount") val messageCount: Int = 0,
    @SerialName("characterId") val characterId: String? = null,
    @SerialName("characterName") val characterName: String? = null,
    // 会话模式（v17 骨架）：默认 classic 保持旧 JSON 反序列化兼容
    val mode: String = SESSION_MODE_CLASSIC,
)

@Immutable
@Serializable
data class Message(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val thinking: String? = null,
    // 这条消息的归属角色（群聊=说话 NPC id；classic=会话主角色；空串=按主角色处理）。
    // 默认空串保持旧 JSON 反序列化兼容
    @SerialName("characterId") val characterId: String = "",
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("memoryIds") val memoryIds: List<String>? = null,
    val swipes: List<String>? = null,
    @SerialName("swipeIndex") val swipeIndex: Int = 0,
    @SerialName("thinkingSwipes") val thinkingSwipes: List<String>? = null,
    // 本条消息的真实用量（仅本地展示链路：API → Room → UI）。
    // 非流式来自 ProtocolContent.usage；流式来自聊天流合并后的最终用量。
    // @Transient：不参与对外 StateResponse JSON 序列化/反序列化，
    // 避免污染 Web 对接/导出消费者契约；真值持久化在 Room（messages.usage_json）。
    @Transient val usage: Usage? = null,
)

@Serializable
data class Memory(
    val id: String = "",
    val content: String = "",
    val type: String = "",
    val layer: String = "",
    val subject: String = "",
    val relation: String = "",
    val `object`: String = "",
    val importance: Double = 0.5,
    val stability: Double = 1.0,
    val status: String = "active",
    @SerialName("accessCount") val accessCount: Int = 0,
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
)

@Serializable
data class WorldBook(
    val id: String = "",
    val name: String = "",
    val entries: List<WorldBookEntry> = emptyList(),
    val books: List<WorldBook> = emptyList(),
)

@Serializable
data class WorldBookEntry(
    val id: String = "",
    val comment: String = "",
    val key: List<String> = emptyList(),
    val content: String = "",
    val constant: Boolean = false,
    val disable: Boolean = false,
    val order: Int = 100,
    @SerialName("insertPosition") val insertPosition: String = "before_prompt",
    // 0=跟随插入位置；1-10=@D 模式（历史倒数第 depth 条之前）。与实体列默认值一致
    val depth: Int = 0,
    // 触发概率 0-1（1=必触发）；与实体列默认值一致
    val probability: Double = 1.0,
    // @D 插入角色：system/user/assistant（仅 depth≥1 生效）；导出键名为酒馆生态的 "role"
    @SerialName("role") val depthRole: String = "system",
    @SerialName("secondaryKeys") val secondaryKeys: List<String> = emptyList(),
    val sticky: Boolean = false,
    val cooldown: Int = 0,
    val delay: Int = 0,
    @SerialName("group") val groupName: String = "",
)

@Serializable
data class SessionConfig(
    @SerialName("providerId") val providerId: String = "",
    @SerialName("modelId") val modelId: String = "",
    @SerialName("worldBookId") val worldBookId: String = "",
)
