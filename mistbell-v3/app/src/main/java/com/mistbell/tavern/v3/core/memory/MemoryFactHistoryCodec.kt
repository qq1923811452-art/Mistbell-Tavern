package com.mistbell.tavern.v3.core.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Versioned, bounded, checksummed representation of temporal fact history. */
object MemoryFactHistoryCodec {
    const val MAX_HISTORY_BYTES = 8 * 1024 * 1024
    const val MAX_FACTS = 50_000
    private const val MAGIC = 0x4D464833 // MFH3
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32
    private const val MAX_STRING_BYTES = 8 * 1024

    fun encode(history: List<MemoryFact>): ByteArray {
        require(history.size <= MAX_FACTS) { "Fact history contains too many records." }
        require(history.map(MemoryFact::id).distinct().size == history.size) {
            "Fact IDs must be unique."
        }
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(history.size)
            history.forEach { fact ->
                writeString(out, fact.id)
                writeString(out, fact.subject)
                writeString(out, fact.predicate)
                writeString(out, fact.value)
                writeString(out, fact.sourceTurnId)
                out.writeLong(fact.recordedAtEpochMillis)
                out.writeInt(fact.status.ordinal)
                out.writeBoolean(fact.supersededAtEpochMillis != null)
                fact.supersededAtEpochMillis?.let(out::writeLong)
                writeNullableString(out, fact.supersededByFactId)
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
            require(it.size <= MAX_HISTORY_BYTES) { "Fact history exceeds the size limit." }
        }
    }

    fun decode(bytes: ByteArray): List<MemoryFact> {
        require(bytes.size <= MAX_HISTORY_BYTES) { "Fact history exceeds the size limit." }
        require(bytes.size >= 4 + 12 + CHECKSUM_BYTES) { "Fact history is truncated." }
        val envelope = DataInputStream(ByteArrayInputStream(bytes))
        val payloadLength = envelope.readInt()
        require(payloadLength >= 12 && payloadLength == bytes.size - 4 - CHECKSUM_BYTES) {
            "Fact history length is invalid."
        }
        val payload = ByteArray(payloadLength)
        envelope.readFully(payload)
        val checksum = ByteArray(CHECKSUM_BYTES)
        envelope.readFully(checksum)
        require(envelope.available() == 0) { "Fact history contains trailing data." }
        val expected = MessageDigest.getInstance("SHA-256").digest(payload)
        require(MessageDigest.isEqual(checksum, expected)) { "Fact history integrity check failed." }

        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Fact history magic does not match." }
        require(input.readInt() == VERSION) { "Fact history version is unsupported." }
        val count = input.readInt()
        require(count in 0..MAX_FACTS) { "Fact count is invalid." }
        val history = List(count) {
            val id = readString(input)
            val subject = readString(input)
            val predicate = readString(input)
            val value = readString(input)
            val sourceTurnId = readString(input)
            val recordedAt = input.readLong()
            val statusOrdinal = input.readInt()
            require(statusOrdinal in FactStatus.entries.indices) { "Fact status is invalid." }
            val supersededAt = if (input.readBoolean()) input.readLong() else null
            val supersededBy = readNullableString(input)
            MemoryFact(
                id = id,
                subject = subject,
                predicate = predicate,
                value = value,
                sourceTurnId = sourceTurnId,
                recordedAtEpochMillis = recordedAt,
                status = FactStatus.entries[statusOrdinal],
                supersededAtEpochMillis = supersededAt,
                supersededByFactId = supersededBy,
            )
        }
        require(input.available() == 0) { "Fact history payload contains trailing data." }
        require(history.map(MemoryFact::id).distinct().size == history.size) {
            "Fact IDs must be unique."
        }
        return history
    }

    private fun writeNullableString(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        value?.let { writeString(out, it) }
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Fact history field exceeds the size limit." }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES) { "Fact history field length is invalid." }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
