package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAIEmbeddingServiceTest {
    @Test
    fun `orders vectors by complete response indexes`() {
        assertEquals(listOf(1, 2, 0), validateEmbeddingResponseOrder(listOf(2, 0, 1), 3))
    }

    @Test
    fun `preserves response order when compatible gateway omits every index`() {
        assertEquals(listOf(0, 1), validateEmbeddingResponseOrder(listOf(null, null), 2))
    }

    @Test
    fun `rejects duplicate or missing response indexes`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEmbeddingResponseOrder(listOf(0, 0), 2)
        }
    }

    @Test
    fun `rejects partially indexed responses`() {
        assertThrows(IllegalStateException::class.java) {
            validateEmbeddingResponseOrder(listOf(0, null), 2)
        }
    }

    @Test
    fun `rejects a response count that differs from request count`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEmbeddingResponseOrder(listOf(0), 2)
        }
    }
}
