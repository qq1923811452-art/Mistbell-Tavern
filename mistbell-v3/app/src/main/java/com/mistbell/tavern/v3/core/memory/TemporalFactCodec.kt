package com.mistbell.tavern.v3.core.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Bounded, versioned, checksummed binary format for the temporal fact ledger. */
object TemporalFactCodec {
    const val MAX_STORE_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x4D544631 // MTF1
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32
    private const val MAX_STRING_BYTES = 8 * 1024

    fun encode(facts: List<TemporalMemoryFact>): ByteArray {
        require(facts.size <= TemporalFactLedger.MAX_FACTS) { "Too many temporal facts." }
        require(facts.map(TemporalMemoryFact::id).distinct().size == facts.size) {
            "Temporal fact IDs must be unique."
        }
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(facts.size)
            facts.sortedWith(compareBy<TemporalMemoryFact> { it.validFromEpochMillis }.thenBy { it.id })
                .forEach { fact ->
                    writeString(out, fact.id)
                    writeString(out, fact.conversationId)
                    writeString(out, fact.sourceTurnId)
                    writeString(out, fact.subject)
                    writeString(out, fact.predicate)
                    writeString(out, fact.value)
                    out.writeLong(fact.observedAtEpochMillis)
                    out.writeLong(fact.validFromEpochMillis)
                    out.writeBoolean(fact.validUntilEpochMillis != null)
                    fact.validUntilEpochMillis?.let(out::writeLong)
                }
        }
        val payload = payloadBuffer.toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val envelope = ByteArrayOutputStream(payload.size + 4 + checksum.size)
        DataOutputStream(envelope).use { out ->
            out.writeInt(payload.size)
            out.write(payload)
            out.write(checksum)
        }
        return envelope.toByteArray().also {
            require(it.size <= MAX_STORE_BYTES) { "Temporal fact store exceeds the size limit." }
        }
    }

    fun decode(bytes: ByteArray): List<TemporalMemoryFact> {
        require(bytes.size <= MAX_STORE_BYTES) { "Temporal fact store exceeds the size limit." }
        require(bytes.size >= 4 + 12 + CHECKSUM_BYTES) { "Temporal fact store is truncated." }
        val envelope = DataInputStream(ByteArrayInputStream(bytes))
        val payloadLength = envelope.readInt()
        require(payloadLength >= 12 && payloadLength == bytes.size - 4 - CHECKSUM_BYTES) {
            "Temporal fact store length is invalid."
        }
        val payload = ByteArray(payloadLength)
        envelope.readFully(payload)
        val checksum = ByteArray(CHECKSUM_BYTES)
        envelope.readFully(checksum)
        require(envelope.available() == 0) { "Temporal fact store contains trailing data." }
        require(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload))) {
            "Temporal fact store integrity check failed."
        }

        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Temporal fact store magic does not match." }
        require(input.readInt() == VERSION) { "Temporal fact store version is unsupported." }
        val count = input.readInt()
        require(count in 0..TemporalFactLedger.MAX_FACTS) { "Temporal fact count is invalid." }
        val facts = List(count) {
            val id = readString(input)
            val conversationId = readString(input)
            val sourceTurnId = readString(input)
            val subject = readString(input)
            val predicate = readString(input)
            val value = readString(input)
            val observedAt = input.readLong()
            val validFrom = input.readLong()
            val validUntil = if (input.readBoolean()) input.readLong() else null
            TemporalMemoryFact(
                id = id,
                conversationId = conversationId,
                sourceTurnId = sourceTurnId,
                subject = subject,
                predicate = predicate,
                value = value,
                observedAtEpochMillis = observedAt,
                validFromEpochMillis = validFrom,
                validUntilEpochMillis = validUntil,
            )
        }
        require(input.available() == 0) { "Temporal fact payload contains trailing data." }
        require(facts.map(TemporalMemoryFact::id).distinct().size == facts.size) {
            "Temporal fact IDs must be unique."
        }
        return facts
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES) {
            "Temporal fact field is empty or too large."
        }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_STRING_BYTES) { "Temporal fact field length is invalid." }
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
