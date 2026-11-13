package com.mistbell.tavern.v3.feature.chat

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Hashes roles and length-framed content; diagnostic logs never include the prompt text. */
internal object PromptCacheDiagnostics {
    fun describe(
        messages: List<PromptMessage>,
        pinnedMessageCount: Int,
    ): String {
        val system = messages.takeWhile { it.role == PromptRole.SYSTEM }
        val prefix = messages.take(system.size + pinnedMessageCount.coerceAtLeast(0))
        return "systemHash=${hash(system)}; prefixHash=${hash(prefix)}; " +
            "prefixMessages=${prefix.size}; prefixUtf16Chars=${prefix.sumOf { it.content.length.toLong() }}"
    }

    private fun hash(messages: List<PromptMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEach { message ->
            val bytes = message.content.toByteArray(Charsets.UTF_8)
            digest.update(message.role.ordinal.toByte())
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
