package com.mistbell.tavern.v3.feature.chat

import java.security.MessageDigest

/** A disposable cache, never an authority for transcript content. */
internal interface WindowStateCache {
    fun load(scopeId: String): ConversationWindowState?
    fun save(state: ConversationWindowState, replaceExisting: Boolean = false)
}

internal data class ConversationWindowState(
    val version: Int,
    val scopeId: String,
    val config: HistoryWindowConfig,
    val sourceCount: Int,
    val sourceFingerprint: String,
    val startId: String?,
    val summaryThroughId: String?,
    val archivedMessageCount: Int,
    val pinnedMessageCount: Int,
    val summary: String,
) {
    fun matches(scope: String, history: List<WindowHistoryMessage>, currentConfig: HistoryWindowConfig): Boolean {
        val sourceFits = sourceCount in 0..history.size
        val matchingMetadata = version == VERSION && scopeId == scope && config == currentConfig
        if (!matchingMetadata || !sourceFits) return false
        val prefix = history.take(sourceCount)
        val messages = prefix.filter { it.content.isNotBlank() }
        val validBoundary = archivedMessageCount in 0..messages.size &&
            pinnedMessageCount in 0..archivedMessageCount
        val next = messages.getOrNull(archivedMessageCount)
        val expectedStartId = next?.id
        val expectedThroughId = messages.getOrNull(archivedMessageCount - 1)?.id
        val boundaryIdsMatch = expectedStartId == startId && expectedThroughId == summaryThroughId
        val pinWholeTurn = pinnedMessageCount == 0 ||
            (pinnedMessageCount in 0..messages.size &&
                (pinnedMessageCount == messages.size || messages[pinnedMessageCount].role == PromptRole.USER))
        val wholeTurn = pinWholeTurn &&
            (next == null || archivedMessageCount == pinnedMessageCount || next.role == PromptRole.USER)
        val safeSummary = summary.length <= config.summaryMaxChars
        // The summary covers only the middle gap; a pin-only window has no summary.
        val summaryConsistent = archivedMessageCount > pinnedMessageCount || summary.isEmpty()
        val matchingSource = sourceFingerprint == WindowSourceFingerprint.hash(prefix)
        return listOf(
            validBoundary, safeSummary, summaryConsistent, boundaryIdsMatch, wholeTurn, matchingSource,
        ).all { it }
    }

    companion object {
        const val VERSION = 2
    }
}

/** Length-framed UTF-16 hashes include whitespace, every field, and variant order/count. */
internal object WindowSourceFingerprint {
    fun hash(history: List<WindowHistoryMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        digest.addInt(history.size)
        history.forEach { message ->
            digest.addString(message.id, buffer)
            digest.addString(message.role.name, buffer)
            digest.addString(message.content, buffer)
            digest.addInt(message.variants.size)
            message.variants.forEach { digest.addString(it, buffer) }
            digest.addInt(message.activeVariant)
            digest.addString(message.sourceRole, buffer)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun MessageDigest.addString(value: String, buffer: ByteArray) {
        addInt(value.length)
        var used = 0
        value.forEach { char ->
            buffer[used++] = (char.code ushr BYTE_BITS).toByte()
            buffer[used++] = char.code.toByte()
            if (used == buffer.size) {
                update(buffer)
                used = 0
            }
        }
        if (used > 0) update(buffer, 0, used)
    }

    private fun MessageDigest.addInt(value: Int) {
        for (byte in (Int.SIZE_BYTES - 1) downTo 0) update((value ushr (byte * BYTE_BITS)).toByte())
    }
}

private const val HASH_BUFFER_BYTES = 4_096
private const val BYTE_BITS = 8
