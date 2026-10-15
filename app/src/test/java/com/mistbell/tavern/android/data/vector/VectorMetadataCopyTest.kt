package com.mistbell.tavern.android.data.vector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMetadataCopyTest {
    @Test
    fun copyMetadataSeparatesNestedMutableCollectionsAndArrays() {
        val nestedList = mutableListOf<Any>("before", mutableListOf("nested"))
        val nestedMap = mutableMapOf("values" to mutableListOf("original"))
        val vector = floatArrayOf(1f, 2f)
        val original = mapOf(
            "list" to nestedList,
            "map" to nestedMap,
            "vector" to vector,
        )

        val copy = InMemoryVectorStore.copyMetadata(original)

        nestedList.add("after")
        (nestedList[1] as MutableList<String>).add("changed")
        nestedMap.getValue("values").add("changed")
        vector[0] = 99f

        assertEquals(listOf("before", listOf("nested")), copy["list"])
        assertEquals(listOf("original"), (copy["map"] as Map<*, *>).getValue("values"))
        assertArrayEquals(floatArrayOf(1f, 2f), copy["vector"] as FloatArray, 0f)
    }
}
