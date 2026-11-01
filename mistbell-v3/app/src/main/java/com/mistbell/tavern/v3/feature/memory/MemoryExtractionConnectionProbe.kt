package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One small, side-effect-free extraction request used by the settings screen.
 *
 * It deliberately bypasses the queue and the fact stores: a probe may spend one request, but it
 * must never create memory facts or change retry state. A syntactically valid `{"facts":[]}` is a
 * successful connection test because extraction is allowed to find nothing in the sample.
 */
internal object MemoryExtractionConnectionProbe {
    data class Result(val success: Boolean, val detail: String)

    fun sampleInput(): MemoryExtractionInput =
        MemoryExtractionInput(
            conversationId = "memory-connection-test",
            turns = listOf(EligibleConversationTurn(id = "connection-test-turn", ordinal = 1)),
            textByTurnId =
                mapOf(
                    "connection-test-turn" to
                        "User: 我喜欢乌龙茶。\nAssistant: 记住了，你喜欢乌龙茶。",
                ),
        )

    suspend fun run(extractor: MemoryFactExtractor): Result {
        val outcome =
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val call =
                        extractor.extract(
                            sampleInput(),
                            MemoryExtractionObserver { result ->
                                if (continuation.isActive) continuation.resume(result)
                            },
                        )
                    continuation.invokeOnCancellation { call.cancel() }
                }
            }
        return when (outcome) {
            is MemoryExtractionOutcome.Success ->
                Result(
                    success = true,
                    detail =
                        "连接成功：模型返回了有效 JSON，解析到 ${outcome.result.facts.size} 条事实。" +
                            "测试请求不会写入记忆。",
                )
            is MemoryExtractionOutcome.Failure ->
                Result(
                    success = false,
                    detail =
                        "连接失败：${outcome.safeMessage}" +
                            (outcome.httpStatus?.let { "（HTTP $it）" } ?: "") +
                            "。详情见诊断日志。",
                )
        }
    }
}
