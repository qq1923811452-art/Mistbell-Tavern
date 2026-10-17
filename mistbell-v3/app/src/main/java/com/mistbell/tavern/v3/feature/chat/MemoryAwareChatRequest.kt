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
) {
    suspend fun stream(
        messages: List<PromptMessage>,
        observer: ChatStreamObserver,
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
        val requestMessages = VectorMemoryPromptComposer.compose(messages, memoryResult)
        return chatClient.stream(requestMessages, observer)
    }
}
