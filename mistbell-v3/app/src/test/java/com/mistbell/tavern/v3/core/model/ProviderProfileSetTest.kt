package com.mistbell.tavern.v3.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderProfileSetTest {
    @Test
    fun eachCapabilityResolvesOnlyItsOwnProfile() {
        val chat = ProviderProfile(
            id = "chat-main",
            kind = ProviderKind.CHAT,
            baseUrl = "https://chat.example.com/v1",
            modelId = "chat-model",
            credentialHandle = "credential-chat",
        )
        val profiles = ProviderProfileSet(listOf(chat))

        assertEquals(chat, profiles.profileFor(ProviderKind.CHAT))
        assertNull(profiles.profileFor(ProviderKind.MEMORY_EXTRACTION))
        assertNull(profiles.profileFor(ProviderKind.EMBEDDING))
        assertNull(profiles.profileFor(ProviderKind.IMAGE_GENERATION))
    }

    @Test
    fun disabledProfileIsNotResolved() {
        val profile = ProviderProfile(
            id = "memory-disabled",
            kind = ProviderKind.MEMORY_EXTRACTION,
            baseUrl = "https://memory.example.com/v1",
            modelId = "memory-model",
            enabled = false,
        )

        assertNull(ProviderProfileSet(listOf(profile)).profileFor(ProviderKind.MEMORY_EXTRACTION))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateCapabilityIsRejected() {
        val first = ProviderProfile(
            id = "chat-a",
            kind = ProviderKind.CHAT,
            baseUrl = "https://a.example.com/v1",
            modelId = "model-a",
        )
        val second = first.copy(id = "chat-b", baseUrl = "https://b.example.com/v1")

        ProviderProfileSet(listOf(first, second))
    }
}
