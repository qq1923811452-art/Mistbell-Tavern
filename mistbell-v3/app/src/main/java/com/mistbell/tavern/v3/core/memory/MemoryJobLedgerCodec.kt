package com.mistbell.tavern.v3.core.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Versioned, bounded and checksummed encoding for the durable memory-job ledger. */
object MemoryJobLedgerCodec {
    const val MAX_LEDGER_BYTES = 4 * 1024 * 1024
    const val MAX_JOBS = 10_000
    const val MAX_TURNS_PER_JOB = 20
    private const val MAGIC = 0x4D4A4C33 // MJL3
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32
    private const val MAX_STRING_BYTES = 16 * 1024

    fun encode(ledger: MemoryJobLedger): ByteArray {
        require(ledger.jobs.size <= MAX_JOBS) { "Memory job ledger contains too many jobs." }
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(ledger.jobs.size)
            ledger.jobs.forEach { job ->
                val plan = job.plan
                writeString(out, plan.id)
                writeString(out, plan.conversationId)
                require(plan.turns.size in 1..MAX_TURNS_PER_JOB) { "Memory job turn count is invalid." }
                out.writeInt(plan.turns.size)
                plan.turns.forEach { turn ->
                    writeString(out, turn.id)
                    out.writeLong(turn.ordinal)
                }
                out.writeInt(job.status.ordinal)
                out.writeInt(job.attemptCount)
                out.writeLong(job.nextAttemptAtEpochMillis)
                writeNullableString(out, job.lastSafeError)
                out.writeBoolean(job.completedAtEpochMillis != null)
                job.completedAtEpochMillis?.let(out::writeLong)
            }
        }
        val payload = payloadBuffer.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val envelope = ByteArrayOutputStream(payload.size + 4 + digest.size)
        DataOutputStream(envelope).use { out ->
            out.writeInt(payload.size)
            out.write(payload)
            out.write(digest)
        }
        return envelope.toByteArray().also {
            require(it.size <= MAX_LEDGER_BYTES) { "Memory job ledger exceeds the size limit." }
        }
    }

    fun decode(bytes: ByteArray): MemoryJobLedger {
        require(bytes.size <= MAX_LEDGER_BYTES) { "Memory job ledger exceeds the size limit." }
        require(bytes.size >= 4 + 8 + CHECKSUM_BYTES) { "Memory job ledger is truncated." }
        val envelope = DataInputStream(ByteArrayInputStream(bytes))
        val payloadLength = envelope.readInt()
        require(payloadLength >= 12 && payloadLength == bytes.size - 4 - CHECKSUM_BYTES) {
            "Memory job ledger length is invalid."
        }
        val payload = ByteArray(payloadLength)
        envelope.readFully(payload)
        val checksum = ByteArray(CHECKSUM_BYTES)
        envelope.readFully(checksum)
        require(envelope.available() == 0) { "Memory job ledger contains trailing data." }
        val expected = MessageDigest.getInstance("SHA-256").digest(payload)
        require(MessageDigest.isEqual(checksum, expected)) { "Memory job ledger integrity check failed." }

        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Memory job ledger magic does not match." }
        require(input.readInt() == VERSION) { "Memory job ledger version is unsupported." }
        val count = input.readInt()
        require(count in 0..MAX_JOBS) { "Memory job count is invalid." }
        val jobs = ArrayList<MemoryExtractionJob>(count)
        repeat(count) {
            val planId = readString(input)
            val conversationId = readString(input)
            val turnCount = input.readInt()
            require(turnCount in 1..MAX_TURNS_PER_JOB) { "Memory job turn count is invalid." }
            val turns = List(turnCount) {
                EligibleConversationTurn(readString(input), input.readLong())
            }
            val statusOrdinal = input.readInt()
            require(statusOrdinal in MemoryJobStatus.entries.indices) { "Memory job status is invalid." }
            val status = MemoryJobStatus.entries[statusOrdinal]
            val attempts = input.readInt()
            val nextAttemptAt = input.readLong()
            val safeError = readNullableString(input)
            val completedAt = if (input.readBoolean()) input.readLong() else null
            jobs += MemoryExtractionJob(
                plan = MemoryBatchPlan(planId, conversationId, turns),
                status = status,
                attemptCount = attempts,
                nextAttemptAtEpochMillis = nextAttemptAt,
                lastSafeError = safeError,
                completedAtEpochMillis = completedAt,
            )
        }
        require(input.available() == 0) { "Memory job ledger payload contains trailing data." }
        return MemoryJobLedger(jobs)
    }

    private fun writeNullableString(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        value?.let { writeString(out, it) }
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Memory ledger string exceeds the size limit." }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES) { "Memory ledger string length is invalid." }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
