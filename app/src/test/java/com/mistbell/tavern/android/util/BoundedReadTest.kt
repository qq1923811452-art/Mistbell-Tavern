package com.mistbell.tavern.android.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedReadTest {
    @Test
    fun readsContentAtTheExactLimit() {
        val bytes = byteArrayOf(1, 2, 3)

        assertArrayEquals(bytes, BoundedRead.bytes(ByteArrayInputStream(bytes), maxBytes = bytes.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsContentOverTheLimit() {
        BoundedRead.bytes(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLimits() {
        BoundedRead.bytes(ByteArrayInputStream(byteArrayOf(1)), maxBytes = -1)
    }

    @Test
    fun decodesBoundedTextAsUtf8() {
        val content = "酒馆 memory".toByteArray(Charsets.UTF_8)

        assertEquals("酒馆 memory", BoundedRead.text(ByteArrayInputStream(content), maxBytes = content.size))
    }
}
