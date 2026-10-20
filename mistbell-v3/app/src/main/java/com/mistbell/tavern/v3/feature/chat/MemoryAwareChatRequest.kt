package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.VectorMemoryContextResult
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextService
import kotlinx.coroutines.CancellationException
import okhttp3.Call

/**
 * Connects local vector-memory retrieval to the actual outgoing chat request.
 *
 * Call from a coroutine; query embedding is suspendable and storage adapters may perform I/O.
 * Retrieval failure is fail-open for chat (send the original prompt), never a remote embedding
 * fallback. The generated memory section is ephemeral and is not written into chat history.
 */
class MemoryAwareChatRequest(
    private val chatClient: OpenAiCompatibleChatClient,
    private val memoryContext: VectorMemoryContextService,
    private val onMemoryStatus: (String) -> Unit = {},
) {
    suspend fun stream(
        messages: List<PromptMessage>,
        observer: ChatStreamObserver,
        options: ChatGenerationOptions = ChatGenerationOptions(),
    ): Call {
        val latestUserMessage = messages.lastOrNull { it.role == PromptRole.USER }?.content
        val memoryResult = if (latestUserMessage.isNullOrBlank()) {
            VectorMemoryContextResult.NoRelevantMemories
        } else {
            try {
                memoryContext.retrieveForQuery(latestUserMessage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A storage/runtime adapter failure must not prevent ordinary chat from working.
                VectorMemoryContextResult.Unavailable(
                    VectorMemoryContextResult.Reason.UNEXPECTED_FAILURE,
                )
            }
        }
        onMemoryStatus(
            when (memoryResult) {
                is VectorMemoryContextResult.Ready -> "本轮已注入 ${memoryResult.memories.size} 条本地记忆。"
                VectorMemoryContextResult.NoRelevantMemories -> "本轮未检索到相关记忆；聊天继续，不注入无关内容。"
                is VectorMemoryContextResult.Unavailable -> "本轮没有注入记忆（${memoryResult.reason}）；聊天继续，索引或本地模型恢复后可重试。"
            },
        )
        val requestMessages = VectorMemoryPromptComposer.compose(messages, memoryResult)
        return chatClient.stream(requestMessages, observer, options)
    }
}
