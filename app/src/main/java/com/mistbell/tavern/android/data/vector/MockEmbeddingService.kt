package com.mistbell.tavern.android.data.vector

import android.util.Log
import kotlin.random.Random

/**
 * Mock Embedding 服务
 *
 * 用于测试或没有配置 OpenAI API Key 时的回退方案
 * 生成随机向量（仅用于测试，不具备实际语义）
 */
class MockEmbeddingService : EmbeddingService {
    companion object {
        private const val TAG = "MockEmbedding"
        private const val DIMENSION = 1536
    }

    override suspend fun embed(text: String): FloatArray {
        Log.w(TAG, "Using mock embedding service - results will not have semantic meaning")

        // 基于文本内容生成伪随机向量（相同文本生成相同向量）
        val seed = text.hashCode().toLong()
        val random = Random(seed)

        return FloatArray(DIMENSION) {
            random.nextFloat() * 2 - 1 // [-1, 1] 范围
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        Log.w(TAG, "Using mock embedding service for batch - results will not have semantic meaning")

        return texts.map { text ->
            val seed = text.hashCode().toLong()
            val random = Random(seed)
            FloatArray(DIMENSION) {
                random.nextFloat() * 2 - 1
            }
        }
    }

    override fun getDimension(): Int = DIMENSION

    override fun getModelId(): String = "mock:deterministic-random"
}
