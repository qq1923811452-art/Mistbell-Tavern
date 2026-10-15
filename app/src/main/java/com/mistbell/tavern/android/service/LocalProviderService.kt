package com.mistbell.tavern.android.service

import com.mistbell.tavern.android.data.api.LlmClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.service.models.LocalChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 本地 LLM 提供商服务
 *
 * 负责调用远程 LLM API（OpenAI、Anthropic、Google 等）
 * 适配 LlmClient 进行实际的网络请求
 */
class LocalProviderService {
    private val settingsRepo = SettingsRepository()

    /**
     * 聊天完成（流式）
     *
     * 注意：当前 LlmClient 不支持流式，此方法返回完整响应
     */
    suspend fun chatStream(
        messages: List<LocalChatMessage>,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): Flow<String> =
        flow {
            // 获取 LLM 配置
            val config = settingsRepo.getLlmConfig()

            // 使用参数或配置中的默认值
            val actualConfig =
                LlmConfig(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = model ?: config.model,
                    temperature = temperature ?: config.temperature,
                    maxTokens = maxTokens ?: config.maxTokens,
                )

            // 转换消息格式
            val llmMessages =
                messages.map { msg ->
                    com.mistbell.tavern.android.data.api.ChatMessage(
                        role = msg.role,
                        content = msg.content,
                    )
                }

            // 调用 LLM API
            try {
                val response = LlmClient.chat(actualConfig, llmMessages)
                emit(response)
            } catch (e: Exception) {
                emit("[错误] ${e.message}")
            }
        }

    /**
     * 聊天完成（非流式）
     */
    suspend fun chat(
        messages: List<LocalChatMessage>,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): String {
        val config = settingsRepo.getLlmConfig()

        val actualConfig =
            LlmConfig(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = model ?: config.model,
                temperature = temperature ?: config.temperature,
                maxTokens = maxTokens ?: config.maxTokens,
            )

        // 转换消息格式
        val llmMessages =
            messages.map { msg ->
                com.mistbell.tavern.android.data.api.ChatMessage(
                    role = msg.role,
                    content = msg.content,
                )
            }

        return try {
            LlmClient.chat(actualConfig, llmMessages)
        } catch (e: Exception) {
            "[错误] ${e.message}"
        }
    }

    /**
     * 测试连接
     */
    suspend fun testConnection(): Boolean {
        return try {
            val config = settingsRepo.getLlmConfig()
            LlmClient.testConnection(config)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 记忆提取
     *
     * 使用 LLM 从对话中提取记忆
     */
    suspend fun extractMemories(
        messages: List<LocalChatMessage>,
        characterName: String,
    ): List<String> {
        val systemPrompt =
            """
            你是一个记忆提取助手。从以下对话中提取重要的记忆信息。

            提取规则：
            1. 提取关于 $characterName 的新信息
            2. 提取用户的偏好、习惯、背景信息
            3. 提取重要的事件和关系
            4. 每条记忆应该简洁明了
            5. 以列表形式返回，每行一条记忆

            示例输出：
            - Alice 喜欢喝咖啡
            - 用户在一家科技公司工作
            - Alice 和 Bob 是朋友关系
            """.trimIndent()

        val extractMessages =
            listOf(
                LocalChatMessage(role = "system", content = systemPrompt),
            ) + messages +
                listOf(
                    LocalChatMessage(role = "user", content = "请提取上述对话中的重要记忆。"),
                )

        val response = chat(extractMessages)

        // 解析响应为记忆列表
        return response
            .lines()
            .filter { it.trim().startsWith("-") }
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * 会话总结
     *
     * 使用 LLM 生成会话总结
     */
    suspend fun summarizeSession(
        messages: List<LocalChatMessage>,
        targetLength: Int = 2200,
    ): String {
        val systemPrompt =
            """
            你是一个对话总结助手。请总结以下对话的关键内容。

            总结要求：
            1. 保留重要的事件和信息
            2. 保持时间顺序
            3. 简洁明了
            4. 目标长度约 $targetLength 字符
            """.trimIndent()

        val summaryMessages =
            listOf(
                LocalChatMessage(role = "system", content = systemPrompt),
            ) + messages +
                listOf(
                    LocalChatMessage(role = "user", content = "请总结上述对话。"),
                )

        return chat(summaryMessages, maxTokens = 1000)
    }
}
