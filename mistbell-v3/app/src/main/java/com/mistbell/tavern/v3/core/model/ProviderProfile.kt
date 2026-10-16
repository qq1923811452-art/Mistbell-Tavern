package com.mistbell.tavern.v3.core.model

enum class ProviderKind {
    CHAT,
    MEMORY_EXTRACTION,
    EMBEDDING,
    IMAGE_GENERATION,
}

/**
 * Provider settings contain only an opaque credential handle, never the API key itself.
 * Each capability resolves its own profile; callers must not silently fall back to CHAT.
 */
data class ProviderProfile(
    val id: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val modelId: String,
    val credentialHandle: String? = null,
    val enabled: Boolean = true,
)

class ProviderProfileSet(profiles: Collection<ProviderProfile>) {
    private val profilesByKind: Map<ProviderKind, ProviderProfile>

    init {
        require(profiles.map(ProviderProfile::kind).distinct().size == profiles.size) {
            "Only one active profile per provider capability is supported."
        }
        require(profiles.map(ProviderProfile::id).distinct().size == profiles.size) {
            "Provider profile IDs must be unique."
        }
        profilesByKind = profiles.associateBy(ProviderProfile::kind)
    }

    fun profileFor(kind: ProviderKind): ProviderProfile? =
        profilesByKind[kind]?.takeIf(ProviderProfile::enabled)
}
