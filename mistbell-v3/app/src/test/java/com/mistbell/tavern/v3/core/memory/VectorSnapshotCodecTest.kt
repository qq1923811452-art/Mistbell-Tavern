package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

        assertEquals(snapshot, VectorSnapshotCodec.decode(VectorSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun recordLimitMatchesMemoryTextStoreLimit() {
        assertEquals(MemoryTextCodec.MAX_RECORDS, VectorSnapshotCodec.MAX_RECORDS)
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
