package com.mistbell.tavern.v3.core.model

enum class ProviderKind {
    CHAT,
    MEMORY_EXTRACTION,
    EMBEDDING,
    IMAGE_GENERATION,
}

/**
 * Provider settings contain only an opaque credential handle, never the API key itself.
 * A user may save multiple profiles per capability, but each capability resolves only
 * the explicitly selected profile. There is no implicit fallback from one capability to another.
 */
data class ProviderProfile(
    val id: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val modelId: String,
    val credentialHandle: String? = null,
    val enabled: Boolean = true,
    /** Providers that buffer SSE responses may prefer the non-streaming endpoint. */
    val supportsStreaming: Boolean = true,
    /** JSON merged into the request body when thinking mode is enabled for this profile. */
    val thinkingBodyTemplate: String? = null,
)

class ProviderProfileSet(
    profiles: Collection<ProviderProfile>,
    selectedProfileIds: Map<ProviderKind, String> = emptyMap(),
) {
    private val profilesById: Map<String, ProviderProfile>
    private val selectedIds = selectedProfileIds.toMap()

    init {
        require(profiles.map(ProviderProfile::id).distinct().size == profiles.size) {
            "Provider profile IDs must be unique."
        }
        profilesById = profiles.associateBy(ProviderProfile::id)
        selectedIds.forEach { (kind, id) ->
            val profile = requireNotNull(profilesById[id]) {
                "Selected provider profile '$id' does not exist."
            }
            require(profile.kind == kind) {
                "Selected provider profile '$id' does not belong to $kind."
            }
        }
    }

    fun profileFor(kind: ProviderKind): ProviderProfile? =
        selectedIds[kind]
            ?.let(profilesById::get)
            ?.takeIf(ProviderProfile::enabled)
}
