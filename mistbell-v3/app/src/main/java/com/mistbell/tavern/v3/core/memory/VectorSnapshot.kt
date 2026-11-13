package com.mistbell.tavern.v3.core.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal interface FloatVectorView {
    fun fillInto(
        destination: FloatArray,
        destinationOffset: Int = 0,
    )
}

/**
 * A mapped vector that stores one signed byte per dimension plus a single positive scale.
 *
 * Because every dimension shares the same positive scale, cosine similarity is unchanged when the
 * scale is dropped: `cos(u, v) = (u . b) / (|u| * |b|)` for `v = scale * b`. Scoring therefore reads
 * the mapped bytes directly and never materialises a dequantized `FloatArray`.
 */
internal interface QuantizedVectorView {
    /** False for legacy float32 snapshots, which must use the dequantizing path. */
    val isQuantized: Boolean

    /**
     * Dot product with [query], read straight from the mapped bytes.
     *
     * The stored vector is `scale * q` with one positive `scale`, so the cosine numerator is this
     * value times `scale` while the denominator carries the same `scale`: dividing by [byteNorm]
     * cancels it. [query] must be finite and match [AbstractList.size].
     */
    fun dot(query: FloatArray): Double

    /**
     * Euclidean norm of the stored bytes, computed on first use and cached per mapped vector.
     *
     * A query must not pay this cost again. Dequantizing every candidate on every search was the
     * dominant cost of the previous path. Returns 0.0 for a stored zero vector.
     */
    fun byteNorm(): Double
}

private class MappedVectorValues(
    private val buffer: ByteBuffer,
    private val start: Int,
    override val size: Int,
    private val legacyFloat32: Boolean,
    private val scale: Float,
) : AbstractList<Float>(), FloatVectorView, QuantizedVectorView {
    override val isQuantized: Boolean
        get() = !legacyFloat32

    /** Cached byte-space magnitude. A lost race recomputes the same value, so plain double is safe. */
    @Volatile
    private var cachedByteNorm: Double = Double.NaN

    override fun dot(query: FloatArray): Double {
        require(!legacyFloat32) { "Legacy float32 vectors cannot be scored from quantized bytes." }
        require(query.size == size) { "Query vector dimension does not match the mapped vector." }
        // Only the numerator is read per query; the magnitude is cached by [byteNorm].
        var dot = 0.0
        for (index in 0 until size) {
            dot += query[index].toDouble() * buffer.get(start + index).toDouble()
        }
        return dot
    }

    override fun byteNorm(): Double {
        val cached = cachedByteNorm
        if (!cached.isNaN()) return cached
        require(!legacyFloat32) { "Legacy float32 vectors do not use byte-space norms." }
        var squared = 0.0
        for (index in 0 until size) {
            val value = buffer.get(start + index).toInt()
            squared += (value * value).toDouble()
        }
        val computed = kotlin.math.sqrt(squared)
        cachedByteNorm = computed
        return computed
    }

    override fun get(index: Int): Float {
        if (index !in 0 until size) throw IndexOutOfBoundsException(index)
        val value =
            if (legacyFloat32) {
                buffer.getFloat(start + index * Float.SIZE_BYTES)
            } else {
                buffer.get(start + index).toFloat() * scale
            }
        require(value.isFinite()) { "Mapped vector contains a non-finite value." }
        return value
    }

    override fun fillInto(
        destination: FloatArray,
        destinationOffset: Int,
    ) {
        require(destinationOffset >= 0 && destinationOffset <= destination.size - size) {
            "Destination array is too small for the mapped vector."
        }
        for (index in 0 until size) {
            val value =
                if (legacyFloat32) {
                    buffer.getFloat(start + index * Float.SIZE_BYTES)
                } else {
                    buffer.get(start + index).toFloat() * scale
                }
            require(value.isFinite()) { "Mapped vector contains a non-finite value." }
            destination[destinationOffset + index] = value
        }
    }
}

data class StoredVector(
    val id: String,
    val sourceTextSha256: String,
    val createdAtEpochMillis: Long,
    val values: List<Float>,
) {
    init {
        require(id.isNotBlank()) { "Vector ID must not be blank." }
        require(sourceTextSha256.matches(SHA256_PATTERN)) {
            "Source text hash must be a 64-character lowercase SHA-256 hex digest."
        }
        require(createdAtEpochMillis >= 0) { "Vector timestamp must not be negative." }
        require(values.isNotEmpty()) { "Vector must not be empty." }
        if (values !is MappedVectorValues) {
            require(values.all(Float::isFinite)) { "Vector values must be finite." }
        }
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class VectorSnapshot(
    val modelId: String,
    val dimensions: Int,
    val records: List<StoredVector>,
) {
    /**
     * Magnitudes are immutable for a decoded snapshot, so decode each vector once instead of
     * recomputing every norm on every query. A query previously paid this cost N times per search.
     */
    val norms: DoubleArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val scratch = FloatArray(dimensions)
        DoubleArray(records.size) { index ->
            val values = records[index].values
            var squared = 0.0
            if (values is FloatVectorView) {
                values.fillInto(scratch)
                for (value in scratch) squared += value.toDouble() * value.toDouble()
            } else {
                for (value in values) squared += value.toDouble() * value.toDouble()
            }
            kotlin.math.sqrt(squared)
        }
    }

    init {
        require(modelId.isNotBlank()) { "Embedding model ID must not be blank." }
        require(dimensions in 1..VectorSnapshotCodec.MAX_DIMENSIONS) {
            "Embedding dimension is outside the supported range."
        }
        require(records.map(StoredVector::id).distinct().size == records.size) {
            "Vector IDs must be unique."
        }
        require(records.all { it.values.size == dimensions }) {
            "Every stored vector must match the snapshot dimension."
        }
        require(records.size <= VectorSnapshotCodec.MAX_RECORDS) {
            "Snapshot contains too many vector records."
        }
    }
}

/**
 * Versioned binary format with a SHA-256 integrity check. The checksum detects accidental
 * corruption; it is not an authentication mechanism and must not be treated as one.
 */
object VectorSnapshotCodec {
    const val MAX_DIMENSIONS = 4096
    const val MAX_RECORDS = 1_000_000
    const val MAX_SNAPSHOT_BYTES = 128 * 1024 * 1024

    private const val MAGIC = 0x4D425633 // MBV3
    private const val LEGACY_FORMAT_VERSION = 1
    private const val FORMAT_VERSION = 2
    private const val CHECKSUM_BYTES = 32
    private const val MAX_MAPPED_UTF_BYTES = 65_535
    private const val RECORD_OVERHEAD_BYTES = 77L

    /**
     * Enforces the snapshot's byte budget before allocating per-record objects or vector views.
     * Version 1 stores float32 values; version 2 stores one-byte quantized values.
     */
    internal fun validatePayloadBudget(
        payloadLength: Long,
        count: Int,
        dimensions: Int,
        formatVersion: Int,
    ) {
        require(payloadLength >= 0L) { "Snapshot payload length is invalid." }
        val bytesPerValue = if (formatVersion == LEGACY_FORMAT_VERSION) Float.SIZE_BYTES else 1
        val recordOverheadBytes = RECORD_OVERHEAD_BYTES + (if (formatVersion == FORMAT_VERSION) Float.SIZE_BYTES else 0)
        val maxValues = (payloadLength - count.toLong() * recordOverheadBytes) / bytesPerValue
        require(count.toLong() * dimensions.toLong() <= maxValues) {
            "Snapshot payload is too small for $count records of $dimensions values."
        }
    }

    fun encode(snapshot: VectorSnapshot): ByteArray {
        val payloadBuffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(payloadBuffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(snapshot.modelId)
            out.writeInt(snapshot.dimensions)
            out.writeInt(snapshot.records.size)
            snapshot.records.forEach { record ->
                require(record.values.size == snapshot.dimensions) {
                    "Every persisted vector must match the snapshot dimension."
                }
                require(record.values.isNotEmpty() && record.values.all(Float::isFinite)) {
                    "Persisted vectors must contain only finite values."
                }
                out.writeUTF(record.id)
                out.writeUTF(record.sourceTextSha256)
                out.writeLong(record.createdAtEpochMillis)
                val maxAbs = record.values.maxOf { kotlin.math.abs(it) }
                require(maxAbs.isFinite()) { "Vector magnitude must be finite." }
                val scale =
                    when {
                        maxAbs == 0f -> 1f
                        else -> (maxAbs / 127f).coerceAtLeast(1.17549435E-38f)
                    }
                require(scale.isFinite() && scale > 0f && scale <= Float.MAX_VALUE / 127f) {
                    "Vector quantization scale is invalid."
                }
                out.writeFloat(scale)
                record.values.forEach { value ->
                    out.writeByte(kotlin.math.round(value / scale).toInt().coerceIn(-127, 127))
                }
            }
        }
        val payload = payloadBuffer.toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val result = java.io.ByteArrayOutputStream(payload.size + 4 + checksum.size)
        java.io.DataOutputStream(result).use { out ->
            out.writeInt(payload.size)
            out.write(payload)
            out.write(checksum)
        }
        return result.toByteArray().also {
            require(it.size <= MAX_SNAPSHOT_BYTES) { "Vector snapshot exceeds the size limit." }
        }
    }

    /**
     * Decodes from a read-only/memory-mapped buffer without copying vector payloads to heap arrays.
     * Each vector is a lazy view over the mapped bytes, so the OS pages data in as retrieval reads it.
     */
    fun decodeMapped(source: ByteBuffer): VectorSnapshot {
        val mapped = source.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
        require(mapped.remaining() <= MAX_SNAPSHOT_BYTES) { "Vector snapshot exceeds the size limit." }
        val envelope = mapped.duplicate().order(ByteOrder.BIG_ENDIAN)
        require(envelope.remaining() >= 4 + 8 + CHECKSUM_BYTES) { "Vector snapshot is truncated." }
        val payloadLength = envelope.int
        val payloadStart = envelope.position()
        require(payloadLength >= 8 && payloadLength.toLong() == envelope.remaining().toLong() - CHECKSUM_BYTES) {
            "Vector snapshot length is invalid."
        }
        val payloadEnd = payloadStart + payloadLength
        val payloadView = mapped.duplicate()
        payloadView.position(payloadStart)
        payloadView.limit(payloadEnd)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(payloadView.slice())
        val actualChecksum = ByteArray(CHECKSUM_BYTES)
        val checksumView = mapped.duplicate()
        checksumView.position(payloadEnd)
        checksumView.get(actualChecksum)
        require(MessageDigest.isEqual(actualChecksum, digest.digest())) {
            "Vector snapshot integrity check failed."
        }

        val input = mapped.duplicate().order(ByteOrder.BIG_ENDIAN)
        input.position(payloadStart)
        input.limit(payloadEnd)
        require(input.int == MAGIC) { "Vector snapshot magic does not match." }
        val formatVersion = input.int
        require(formatVersion == LEGACY_FORMAT_VERSION || formatVersion == FORMAT_VERSION) {
            "Vector snapshot format version is unsupported."
        }
        val modelId = readMappedUtf(input)
        val dimensions = input.int
        require(dimensions in 1..MAX_DIMENSIONS) { "Vector dimension is invalid." }
        val count = input.int
        require(count in 0..MAX_RECORDS) { "Vector record count is invalid." }
        validatePayloadBudget(
            payloadLength = payloadLength.toLong(),
            count = count,
            dimensions = dimensions,
            formatVersion = formatVersion,
        )

        val records = ArrayList<StoredVector>(count)
        repeat(count) {
            val id = readMappedUtf(input)
            val sourceHash = readMappedUtf(input)
            val createdAt = input.long
            val scale =
                if (formatVersion == FORMAT_VERSION) {
                    input.float.also {
                        require(it.isFinite() && it > 0f && it <= Float.MAX_VALUE / 127f) {
                            "Vector quantization scale is invalid."
                        }
                    }
                } else {
                    1f
                }
            val vectorStart = input.position()
            val vectorBytes = if (formatVersion == LEGACY_FORMAT_VERSION) dimensions * Float.SIZE_BYTES else dimensions
            require(input.remaining() >= vectorBytes) { "Vector data is truncated." }
            // A signed byte can be -128, although our encoder emits only [-127, 127].
            // Preserve the finite-value contract even when the byte-space scorer skips scaling.
            if (formatVersion == FORMAT_VERSION && !(Byte.MIN_VALUE.toFloat() * scale).isFinite()) {
                repeat(dimensions) { index ->
                    require(input.get(vectorStart + index) != Byte.MIN_VALUE) {
                        "Mapped vector contains a non-finite value."
                    }
                }
            }
            input.position(vectorStart + vectorBytes)
            val values =
                MappedVectorValues(
                    buffer = mapped,
                    start = vectorStart,
                    size = dimensions,
                    legacyFloat32 = formatVersion == LEGACY_FORMAT_VERSION,
                    scale = scale,
                )
            records += StoredVector(id, sourceHash, createdAt, values)
        }
        require(input.position() == payloadEnd) { "Vector snapshot payload contains trailing data." }
        return VectorSnapshot(modelId, dimensions, records)
    }

    private fun readMappedUtf(input: ByteBuffer): String {
        require(input.remaining() >= 2) { "Vector snapshot string is truncated." }
        val length = input.short.toInt() and 0xffff
        require(length in 1..MAX_MAPPED_UTF_BYTES) { "Vector snapshot string length is invalid." }
        require(input.remaining() >= length) { "Vector snapshot string is truncated." }
        val encoded = ByteArray(length + 2)
        encoded[0] = (length ushr 8).toByte()
        encoded[1] = length.toByte()
        input.get(encoded, 2, length)
        return java.io.DataInputStream(encoded.inputStream()).use { it.readUTF() }
    }

    fun decode(bytes: ByteArray): VectorSnapshot {
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "Vector snapshot exceeds the size limit." }
        require(bytes.size >= 4 + 8 + CHECKSUM_BYTES) { "Vector snapshot is truncated." }

        val envelope = java.io.DataInputStream(bytes.inputStream())
        val payloadLength = envelope.readInt()
        require(payloadLength >= 8 && payloadLength == bytes.size - 4 - CHECKSUM_BYTES) {
            "Vector snapshot length is invalid."
        }
        val payload = ByteArray(payloadLength)
        envelope.readFully(payload)
        val checksum = ByteArray(CHECKSUM_BYTES)
        envelope.readFully(checksum)
        require(envelope.available() == 0) { "Vector snapshot contains trailing data." }
        val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(payload)
        require(MessageDigest.isEqual(checksum, expectedChecksum)) {
            "Vector snapshot integrity check failed."
        }

        val input = java.io.DataInputStream(payload.inputStream())
        require(input.readInt() == MAGIC) { "Vector snapshot magic does not match." }
        val formatVersion = input.readInt()
        require(formatVersion == LEGACY_FORMAT_VERSION || formatVersion == FORMAT_VERSION) {
            "Vector snapshot format version is unsupported."
        }
        val modelId = input.readUTF()
        val dimensions = input.readInt()
        require(dimensions in 1..MAX_DIMENSIONS) { "Vector dimension is invalid." }
        val count = input.readInt()
        require(count in 0..MAX_RECORDS) { "Vector record count is invalid." }
        validatePayloadBudget(
            payloadLength = payloadLength.toLong(),
            count = count,
            dimensions = dimensions,
            formatVersion = formatVersion,
        )

        val records = ArrayList<StoredVector>(count)
        repeat(count) {
            val id = input.readUTF()
            val sourceHash = input.readUTF()
            val createdAt = input.readLong()
            val values =
                if (formatVersion == LEGACY_FORMAT_VERSION) {
                    FloatArray(dimensions) { input.readFloat() }.asList()
                } else {
                    val scale = input.readFloat()
                    require(scale.isFinite() && scale > 0f) { "Vector quantization scale is invalid." }
                    FloatArray(dimensions) { input.readByte().toFloat() * scale }.asList()
                }
            require(values.size == dimensions) { "Decoded vector dimension does not match the snapshot." }
            records +=
                StoredVector(
                    id = id,
                    sourceTextSha256 = sourceHash,
                    createdAtEpochMillis = createdAt,
                    values = values,
                )
        }
        require(input.available() == 0) { "Vector snapshot payload contains trailing data." }
        return VectorSnapshot(modelId = modelId, dimensions = dimensions, records = records)
    }
}
