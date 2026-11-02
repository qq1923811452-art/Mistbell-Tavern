package com.mistbell.tavern.v3.core.memory

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Deterministic identity for one extracted observation.
 *
 * The id is a pure function of the conversation scope and the observation's content, so the same
 * turn always yields the same fact id. That idempotence is what lets a rebuild, a retry, or a
 * scope migration re-derive facts without creating duplicates. Anything that rewrites the
 * conversation scope of a stored fact must therefore recompute its id with this object.
 */
object TemporalFactIdentity {
    fun forObservation(
        conversationId: String,
        sourceTurnId: String,
        subject: String,
        predicate: String,
        value: String,
    ): String {
        require(conversationId.isNotBlank() && sourceTurnId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(conversationId, sourceTurnId, subject, predicate, value).forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return "fact:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
