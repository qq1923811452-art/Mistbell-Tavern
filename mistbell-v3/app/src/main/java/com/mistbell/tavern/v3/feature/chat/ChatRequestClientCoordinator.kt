package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore

internal data class ChatRequestClientConfiguration(
    val providers: ProviderProfileSet,
    val client: OpenAiCompatibleChatClient,
)

/**
 * Owns the existing per-request provider profile validation and client construction.
 */
internal class ChatRequestClientCoordinator(
    private val memoryCredentialStore: AndroidKeystoreMemoryCredentialStore,
    private val settings: SharedPreferences,
    private val setStatus: (String) -> Unit,
) {
    fun create(
        baseUrl: String,
        modelId: String,
        apiKey: String,
        memoryBaseUrl: String,
        memoryModelId: String,
        memoryApiKey: String,
    ): ChatRequestClientConfiguration? {
        val profile = ProviderProfile(
            id = "session-chat",
            kind = ProviderKind.CHAT,
            baseUrl = baseUrl.trim(),
            modelId = modelId.trim(),
            credentialHandle = if (apiKey.isBlank()) null else "session-chat-key",
        )
        val secretForThisRequest = apiKey
        val memorySecretForThisRequest = memoryApiKey
        val memoryProfile = memoryModelId.trim().takeIf(String::isNotBlank)?.let { configuredMemoryModel ->
            ProviderProfile(
                id = "session-memory-extraction",
                kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = memoryBaseUrl.trim(),
                modelId = configuredMemoryModel,
                credentialHandle = when {
                    memoryApiKey.isNotBlank() -> "session-memory-key"
                    else -> settings.getString("memory_api_credential_handle", null)
                        ?.takeIf(memoryCredentialStore::contains)
                },
            )
        }
        val providersForRequest = try {
            ProviderProfileSet(
                profiles = listOfNotNull(profile, memoryProfile),
                selectedProfileIds = buildMap {
                    put(ProviderKind.CHAT, profile.id)
                    if (memoryProfile != null) put(ProviderKind.MEMORY_EXTRACTION, memoryProfile.id)
                },
            )
        } catch (_: IllegalArgumentException) {
            setStatus("聊天或记忆 API 配置无效，请检查 Base URL 和模型 ID。")
            return null
        }
        val client = OpenAiCompatibleChatClient(
            providers = providersForRequest,
            credentials = CredentialResolver { handle ->
                when (handle) {
                    "session-chat-key" -> secretForThisRequest
                    "session-memory-key" -> memorySecretForThisRequest
                    else -> null
                }
            },
        )
        return ChatRequestClientConfiguration(providersForRequest, client)
    }
}
