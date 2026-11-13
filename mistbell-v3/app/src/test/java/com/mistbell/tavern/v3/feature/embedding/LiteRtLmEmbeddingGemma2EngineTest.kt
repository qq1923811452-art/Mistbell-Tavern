package com.mistbell.tavern.v3.feature.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class LiteRtLmEmbeddingGemma2EngineTest {
    @Test
    fun cacheDirectoryIdentityIsStableAndSeparatedByModelFile() {
        val first = File("/models/embedding-a.litertlm")
        val second = File("/models/embedding-b.litertlm")

        val firstName = LiteRtLmEmbeddingGemma2Engine.modelCacheDirectoryName(first)
        assertEquals(firstName, LiteRtLmEmbeddingGemma2Engine.modelCacheDirectoryName(first))
        assertNotEquals(firstName, LiteRtLmEmbeddingGemma2Engine.modelCacheDirectoryName(second))
    }
}
