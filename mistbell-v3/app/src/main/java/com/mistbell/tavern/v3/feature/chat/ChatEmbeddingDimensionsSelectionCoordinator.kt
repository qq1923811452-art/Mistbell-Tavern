package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore

/** Persists embedding dimensions and returns the existing user-facing status message. */
internal class ChatEmbeddingDimensionsSelectionCoordinator(
    private val settings: SharedPreferences,
    private val diagnosticStore: DiagnosticLogStore,
) {
    fun select(dimensions: Int): String {
        settings.edit().putInt("embedding_output_dimensions", dimensions).apply()
        diagnosticStore.info("embedding.dimensions.changed", "dimensions=$dimensions")
        return if (dimensions <= 768) {
            "已选择 $dimensions 维输出。切换档位后，已有向量需要按新维度重建。"
        } else {
            "已保留 $dimensions 维档位供更高维模型使用；当前 EmbeddingGemma 2 原生最高为 768 维，超出时会跳过本地记忆并继续普通聊天。"
        }
    }
}
