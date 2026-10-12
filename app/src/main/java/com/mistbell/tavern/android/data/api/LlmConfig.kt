package com.mistbell.tavern.android.data.api

import kotlinx.serialization.Serializable

@Serializable
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.8,
    val maxTokens: Int = 1024,
    // S1 采样细项：null = 未设置（请求体中不出现该字段）
    val topP: Double? = null,
    val topK: Int? = null,
    val frequencyPenalty: Double? = null,
    // S1 请求策略：超时秒数（钳制 15..600）与重试次数（钳制 0..5）
    val timeoutSeconds: Int = 90,
    val retries: Int = 2,
    // 关闭思考模式（DeepSeek OpenAI 格式 {"thinking":{"type":"disabled"}}）。
    // 思维链占输出 token 大头（实测记忆抽取单轮 4K 输出里约 3.5K 是思考），
    // 纯结构化任务（抽取 JSON）关掉可大幅省输出；网关不认该参数时由 LlmClient 自动回退
    val disableThinking: Boolean = false,
    // 接口类型（ApiType：openai/anthropic/gemini/custom），决定 LlmClient 走哪个协议适配
    val type: String = "openai",
    // SSE 流式传输开关：由当前 API 配置决定
    val streamingEnabled: Boolean = true,
)
