package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.core.model.ProviderProfileSet

internal data class PreparedChatSendRequest(
    val userText: String,
    val providers: ProviderProfileSet,
    val client: OpenAiCompatibleChatClient,
)

/** Performs the existing send-button guards and creates request-scoped provider/client state. */
internal class ChatSendRequestPreflightCoordinator(
    private val memoryCredentialStore: AndroidKeystoreMemoryCredentialStore,
    private val settings: SharedPreferences,
    private val setStatus: (String) -> Unit,
) {
    fun prepare(
        userText: String,
        busy: Boolean,
        worldbooksReady: Boolean,
        baseUrl: String,
        modelId: String,
        apiKey: String,
        memoryBaseUrl: String,
        memoryModelId: String,
        memoryApiKey: String,
    ): PreparedChatSendRequest? {
        val normalizedText = userText.trim()
        if (normalizedText.isEmpty() || busy) return null
        if (!worldbooksReady) {
            setStatus("世界书加载中，请稍后再发送。")
            return null
        }
        if (modelId.isBlank()) {
            setStatus("请先填写模型 ID。")
            return null
        }
        val request = ChatRequestClientCoordinator(
            memoryCredentialStore = memoryCredentialStore,
            settings = settings,
            setStatus = setStatus,
        ).create(
            baseUrl = baseUrl,
            modelId = modelId,
            apiKey = apiKey,
            memoryBaseUrl = memoryBaseUrl,
            memoryModelId = memoryModelId,
            memoryApiKey = memoryApiKey,
        ) ?: return null
        return PreparedChatSendRequest(
            userText = normalizedText,
            providers = request.providers,
            client = request.client,
        )
    }
}
