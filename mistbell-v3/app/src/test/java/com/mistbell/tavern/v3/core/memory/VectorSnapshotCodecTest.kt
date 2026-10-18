package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSnapshotCodecTest {
    @Test
    fun roundTripsModelTaggedVectors() {
        val snapshot = VectorSnapshot(
            modelId = "local:embeddinggemma-2:int8",
            dimensions = 3,
            records = listOf(
                StoredVector(
                    id = "vector-1",
                    sourceTextSha256 = HASH,
                    createdAtEpochMillis = 1234,
                    values = listOf(0.25f, -0.5f, 1.0f),
                ),
            ),
        )

        val decoded = VectorSnapshotCodec.decode(VectorSnapshotCodec.encode(snapshot))
        assertEquals(snapshot.modelId, decoded.modelId)
        assertEquals(snapshot.dimensions, decoded.dimensions)
        assertEquals(snapshot.records.map { it.id }, decoded.records.map { it.id })
        snapshot.records.single().values.zip(decoded.records.single().values).forEach { (expected, actual) ->
            org.junit.Assert.assertTrue(kotlin.math.abs(expected - actual) <= 0.01f)
        }
    }

    @Test
    fun snapshotCodecPreservesLegacyRecordLimitForCompatibility() {
        assertEquals(100_000, VectorSnapshotCodec.MAX_RECORDS)
        assertEquals(50_000, MemoryTextCodec.MAX_RECORDS)
    }

    @Test
    fun decodesSnapshotAboveCurrentTextStoreRecordLimit() {
        val records = (0..MemoryTextCodec.MAX_RECORDS).map { index ->
            StoredVector("vector-$index", HASH, index.toLong(), listOf(0.5f))
        }
        val snapshot = VectorSnapshot(modelId = "model-a", dimensions = 1, records = records)

        val decoded = VectorSnapshotCodec.decode(VectorSnapshotCodec.encode(snapshot))

        assertEquals(50_001, decoded.records.size)
        assertEquals("vector-50000", decoded.records.last().id)
    }

    @Test
    fun decodesLegacyFloat32SnapshotAfterInt8FormatUpgrade() {
        val payloadBuffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(payloadBuffer).use { out ->
            out.writeInt(0x4D425633)
            out.writeInt(1)
            out.writeUTF("model-legacy")
            out.writeInt(2)
            out.writeInt(1)
            out.writeUTF("legacy-vector")
            out.writeUTF(HASH)
            out.writeLong(42L)
            out.writeFloat(0.25f)
            out.writeFloat(-0.5f)
        }
        val payload = payloadBuffer.toByteArray()
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        val envelopeBuffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(envelopeBuffer).use { out ->
            out.writeInt(payload.size)
            out.write(payload)
            out.write(checksum)
        }

        val decoded = VectorSnapshotCodec.decode(envelopeBuffer.toByteArray())
        assertEquals("model-legacy", decoded.modelId)
        assertEquals(listOf(0.25f, -0.5f), decoded.records.single().values)
    }

    @Test
    fun int8PersistenceReducesVectorPayloadByAtLeastSeventyPercent() {
        val records = (0 until 10_000).map { index ->
            StoredVector(
                id = "vector-$index",
                sourceTextSha256 = HASH,
                createdAtEpochMillis = index.toLong(),
                values = List(768) { dimension -> kotlin.math.sin(index * 0.13 + dimension * 0.017).toFloat() },
            )
        }
        val encoded = VectorSnapshotCodec.encode(VectorSnapshot("model-int8", 768, records))
        val floatPayloadBytes = records.size.toLong() * 768L * 4L
        assertTrue("encoded=${encoded.size}; floatVectorBytes=$floatPayloadBytes", encoded.size.toLong() < floatPayloadBytes * 0.30)
    }

    @Test
    fun int8RoundTripKeepsTopFiveNearestNeighbors() {
        val records = (0 until 64).map { index ->
            val values = List(128) { dimension ->
                when (dimension) { 0 -> 1.0f - index * 0.02f; 1 -> 0.1f; else -> 0f }
            }
            StoredVector("vector-$index", HASH, index.toLong(), values)
        }
        val original = VectorSnapshot("model-int8", 128, records)
        val restored = VectorSnapshotCodec.decode(VectorSnapshotCodec.encode(original))
        val query = List(128) { dimension -> if (dimension == 0) 1f else 0f }
        fun topFive(snapshot: VectorSnapshot) = VectorSimilaritySearch.search(
            snapshot = snapshot,
            query = query,
            limit = 5,
            minimumScore = -1.0,
        ).map { it.id }.toSet()
        assertTrue(topFive(original).intersect(topFive(restored)).size >= 4)
    }

    @Test
    fun zeroVectorPersistsAndRemainsNonMatchable() {
        val zero = StoredVector("zero", HASH, 1L, List(3) { 0f })
        val snapshot = VectorSnapshot("model-zero", 3, listOf(zero))
        val decoded = VectorSnapshotCodec.decode(VectorSnapshotCodec.encode(snapshot))
        assertEquals(listOf(0f, 0f, 0f), decoded.records.single().values)
        assertTrue(
            VectorSimilaritySearch.search(
                snapshot = decoded,
                query = listOf(1f, 0f, 0f),
                minimumScore = -1.0,
            ).isEmpty(),
        )
    }

    @Test
    fun rejectsChecksumMismatch() {
        val encoded = VectorSnapshotCodec.encode(
            VectorSnapshot(
                modelId = "model-a",
                dimensions = 1,
                records = listOf(
                    StoredVector("v1", HASH, 1, listOf(0.5f)),
                ),
            ),
        )
        encoded[encoded.lastIndex - 1] = (encoded[encoded.lastIndex - 1].toInt() xor 0x01).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            VectorSnapshotCodec.decode(encoded)
        }
    }

    @Test
    fun rejectsVectorsWithWrongDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorSnapshot(
                modelId = "model-a",
                dimensions = 2,
                records = listOf(StoredVector("v1", HASH, 1, listOf(0.5f))),
            )
        }
    }

    @Test
    fun rejectsNonFiniteVectorValues() {
        assertThrows(IllegalArgumentException::class.java) {
            StoredVector("v1", HASH, 1, listOf(Float.NaN))
        }
    }

    companion object {
        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
