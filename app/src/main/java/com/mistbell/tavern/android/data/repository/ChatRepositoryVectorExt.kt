package com.mistbell.tavern.android.data.repository

import android.util.Log
import com.mistbell.tavern.android.TavernApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ChatRepository 扩展 - 向量存储功能
 *
 * 提供消息向量化和语义检索功能
 */

private const val TAG = "ChatRepository"

/**
 * 存储用户消息到向量数据库
 */
suspend fun storeUserMessageVector(
    content: String,
    ownerId: String,
    characterId: String,
    sessionId: String,
    messageId: String,
) = withContext(Dispatchers.IO) {
    // F3-FTS: 无真实 embedding 服务（available=false）时不写伪向量，杜绝新增坏数据（孤儿向量）
    if (TavernApplication.instance.container.vectorMemoryService.available) {
        try {
            val vectorMemoryService = TavernApplication.instance.container.vectorMemoryService

            // VectorMemory.ContentType.USER_MESSAGE
            vectorMemoryService.storeMessage(
                content = content,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = messageId,
                contentType = "user_message",
            )

            Log.d(TAG, "Stored user message vector: $messageId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 向量存储失败不应影响主流程
            Log.e(TAG, "Failed to store user message vector: ${e.message}", e)
        }
    }
}

/**
 * 存储 AI 回复到向量数据库
 */
suspend fun storeAssistantMessageVector(
    content: String,
    ownerId: String,
    characterId: String,
    sessionId: String,
    messageId: String,
) = withContext(Dispatchers.IO) {
    // F3-FTS: 无真实 embedding 服务（available=false）时不写伪向量，杜绝新增坏数据（孤儿向量）
    if (TavernApplication.instance.container.vectorMemoryService.available) {
        try {
            val vectorMemoryService = TavernApplication.instance.container.vectorMemoryService

            // VectorMemory.ContentType.AI_MESSAGE
            vectorMemoryService.storeMessage(
                content = content,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                messageId = messageId,
                contentType = "ai_message",
            )

            Log.d(TAG, "Stored assistant message vector: $messageId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 向量存储失败不应影响主流程
            Log.e(TAG, "Failed to store assistant message vector: ${e.message}", e)
        }
    }
}

suspend fun deleteMessageVectors(messageId: String) =
    withContext(Dispatchers.IO) {
        if (!TavernApplication.instance.container.vectorMemoryService.available) return@withContext
        TavernApplication.instance.container.vectorMemoryService.deleteByMessageId(messageId)
    }

suspend fun deleteSessionVectors(
    ownerId: String,
    characterId: String,
    sessionId: String,
) = withContext(Dispatchers.IO) {
    if (!TavernApplication.instance.container.vectorMemoryService.available) return@withContext
    TavernApplication.instance.container.vectorMemoryService.deleteSessionVectors(ownerId, characterId, sessionId)
}
