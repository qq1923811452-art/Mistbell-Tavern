package com.mistbell.tavern.v3.feature.chat

import java.security.MessageDigest

/** A disposable cache, never an authority for transcript content. */
internal interface WindowStateCache {
    fun load(scopeId: String): ConversationWindowState?
    fun save(state: ConversationWindowState)
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
    val summary: String,
) {
    fun matches(scope: String, history: List<WindowHistoryMessage>, currentConfig: HistoryWindowConfig): Boolean {
        val sourceFits = sourceCount in 0..history.size
        val matchingMetadata = version == VERSION && scopeId == scope && config == currentConfig
        if (!matchingMetadata || !sourceFits) return false
        val prefix = history.take(sourceCount)
        val messages = prefix.filter { it.content.isNotBlank() }
        val validBoundary = archivedMessageCount in 0..messages.size
        val next = messages.getOrNull(archivedMessageCount)
        val boundaryIdsMatch = next?.id == startId &&
            messages.getOrNull(archivedMessageCount - 1)?.id == summaryThroughId
        val wholeTurn = next == null || archivedMessageCount == 0 || next.role == PromptRole.USER
        val safeSummary = summary.length <= config.summaryMaxChars
        // A summary must describe archived messages; nothing archived means no summary.
        val summaryConsistent = archivedMessageCount > 0 || summary.isEmpty()
        val matchingSource = sourceFingerprint == WindowSourceFingerprint.hash(prefix)
        return listOf(
            validBoundary, safeSummary, summaryConsistent, boundaryIdsMatch, wholeTurn, matchingSource,
        ).all { it }
    }

    companion object {
        const val VERSION = 1
    }
}

/** Length-framed UTF-16 hashes include whitespace, every field, and variant order/count. */
internal object WindowSourceFingerprint {
    fun hash(history: List<WindowHistoryMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.addInt(history.size)
        history.forEach { message ->
            digest.addString(message.id)
            digest.addString(message.role.name)
            digest.addString(message.content)
            digest.addInt(message.variants.size)
            message.variants.forEach { digest.addString(it) }
            digest.addInt(message.activeVariant)
            digest.addString(message.sourceRole)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun MessageDigest.addString(value: String) {
        addInt(value.length)
        val buffer = ByteArray(HASH_BUFFER_BYTES)
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
