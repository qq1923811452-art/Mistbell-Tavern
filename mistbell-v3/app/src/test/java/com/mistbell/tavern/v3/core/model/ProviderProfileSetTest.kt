package com.mistbell.tavern.v3.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderProfileSetTest {
    @Test
    fun resolvesOnlyTheExplicitlySelectedProfileForEachCapability() {
        val chat = ProviderProfile(
            id = "chat-main",
            kind = ProviderKind.CHAT,
            baseUrl = "https://chat.example.com/v1",
            modelId = "chat-model",
            credentialHandle = "credential-chat",
        )
        val profiles = ProviderProfileSet(
            profiles = listOf(chat),
            selectedProfileIds = mapOf(ProviderKind.CHAT to chat.id),
        )

        assertEquals(chat, profiles.profileFor(ProviderKind.CHAT))
        assertNull(profiles.profileFor(ProviderKind.MEMORY_EXTRACTION))
        assertNull(profiles.profileFor(ProviderKind.EMBEDDING))
        assertNull(profiles.profileFor(ProviderKind.IMAGE_GENERATION))
    }

    @Test
    fun allowsMultipleSavedProfilesButResolvesOnlyTheSelectedOne() {
        val first = ProviderProfile(
            id = "chat-a",
            kind = ProviderKind.CHAT,
            baseUrl = "https://a.example.com/v1",
            modelId = "model-a",
        )
        val second = first.copy(
            id = "chat-b",
            baseUrl = "https://b.example.com/v1",
            modelId = "model-b",
        )

        val profiles = ProviderProfileSet(
            profiles = listOf(first, second),
            selectedProfileIds = mapOf(ProviderKind.CHAT to second.id),
        )

        assertEquals(second, profiles.profileFor(ProviderKind.CHAT))
    }

    @Test
    fun disabledSelectedProfileIsNotResolved() {
        val profile = ProviderProfile(
            id = "memory-disabled",
            kind = ProviderKind.MEMORY_EXTRACTION,
            baseUrl = "https://memory.example.com/v1",
            modelId = "memory-model",
            enabled = false,
        )

        assertNull(
            ProviderProfileSet(
                profiles = listOf(profile),
                selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
            ).profileFor(ProviderKind.MEMORY_EXTRACTION),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectedProfileMustMatchItsCapability() {
        val chat = ProviderProfile(
            id = "chat-only",
            kind = ProviderKind.CHAT,
            baseUrl = "https://chat.example.com/v1",
            modelId = "chat-model",
        )

        ProviderProfileSet(
            profiles = listOf(chat),
            selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to chat.id),
        )
    }
}
