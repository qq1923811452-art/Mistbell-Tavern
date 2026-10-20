package com.mistbell.tavern.v3.core.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Versioned, bounded, checksummed persistence format for source text linked to local vectors. */
object MemoryTextCodec {
    const val MAX_STORE_BYTES = 8 * 1024 * 1024
    const val MAX_RECORDS = 50_000
    private const val MAGIC = 0x4D545833 // MTX3
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32
    private const val MAX_ID_BYTES = 4 * 1024
    private const val MAX_TEXT_BYTES = 32 * 1024

    fun encode(records: List<MemoryTextRecord>): ByteArray {
        require(records.size <= MAX_RECORDS) { "Memory text store contains too many records." }
        require(records.map(MemoryTextRecord::id).distinct().size == records.size) {
            "Memory text IDs must be unique."
        }
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(records.size)
            records.sortedBy(MemoryTextRecord::id).forEach { record ->
                writeString(out, record.id, MAX_ID_BYTES)
                writeString(out, record.text, MAX_TEXT_BYTES)
                out.write(record.sourceTextSha256.hexToBytes())
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
            require(it.size <= MAX_STORE_BYTES) { "Memory text store exceeds the size limit." }
        }
    }

    fun decode(bytes: ByteArray): List<MemoryTextRecord> {
        require(bytes.size <= MAX_STORE_BYTES) { "Memory text store exceeds the size limit." }
        require(bytes.size >= 4 + 12 + CHECKSUM_BYTES) { "Memory text store is truncated." }
        val envelope = DataInputStream(ByteArrayInputStream(bytes))
        val payloadLength = envelope.readInt()
        require(payloadLength >= 12 && payloadLength == bytes.size - 4 - CHECKSUM_BYTES) {
            "Memory text store length is invalid."
        }
        val payload = ByteArray(payloadLength)
        envelope.readFully(payload)
        val checksum = ByteArray(CHECKSUM_BYTES)
        envelope.readFully(checksum)
        require(envelope.available() == 0) { "Memory text store contains trailing data." }
        require(
            MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload)),
        ) { "Memory text store integrity check failed." }

        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Memory text store magic does not match." }
        require(input.readInt() == VERSION) { "Memory text store version is unsupported." }
        val count = input.readInt()
        require(count in 0..MAX_RECORDS) { "Memory text record count is invalid." }
        val records = List(count) {
            val id = readString(input, MAX_ID_BYTES)
            val text = readString(input, MAX_TEXT_BYTES)
            val expectedHash = ByteArray(32).also(input::readFully).toHex()
            val record = MemoryTextRecord(id, text)
            require(record.sourceTextSha256 == expectedHash) {
                "Memory text content hash does not match."
            }
            record
        }
        require(input.available() == 0) { "Memory text payload contains trailing data." }
        require(records.map(MemoryTextRecord::id).distinct().size == records.size) {
            "Memory text IDs must be unique."
        }
        return records
    }

    private fun writeString(out: DataOutputStream, value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= maximumBytes) {
            "Memory text field is empty or exceeds the size limit."
        }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream, maximumBytes: Int): String {
        val length = input.readInt()
        require(length in 1..maximumBytes) { "Memory text field length is invalid." }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length == 64) { "SHA-256 must contain 64 hexadecimal characters." }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
