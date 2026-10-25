package com.mistbell.tavern.v3.feature.worldbook.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorldbookPlannerRegexCacheTest {
    private val planner = WorldbookPlanner()

    @Before
    fun setUp() = WorldbookPlanner.clearRegexCacheForTest()

    @After
    fun tearDown() = WorldbookPlanner.clearRegexCacheForTest()

    @Test
    fun cacheNeverExceedsItsBound() {
        repeat(WorldbookPlanner.MAX_REGEX_CACHE_ENTRIES_FOR_TEST + 64) { index ->
            planner.matchingKeys(listOf("word$index*"), "word$index content")
        }
        assertTrue(WorldbookPlanner.regexCacheSizeForTest() <= WorldbookPlanner.MAX_REGEX_CACHE_ENTRIES_FOR_TEST)
    }

    @Test
    fun recentlyUsedEntrySurvivesEviction() {
        planner.matchingKeys(listOf("/keep-me/"), "keep-me")
        repeat(WorldbookPlanner.MAX_REGEX_CACHE_ENTRIES_FOR_TEST) { index ->
            planner.matchingKeys(listOf("filler$index*"), "filler$index")
        }
        // Touch the first entry so it becomes the most recently used.
        planner.matchingKeys(listOf("/keep-me/"), "keep-me")
        planner.matchingKeys(listOf("one-more*"), "one more")
        assertTrue(WorldbookPlanner.regexCacheContainsForTest("raw:keep-me"))
        assertFalse(WorldbookPlanner.regexCacheContainsForTest("wild:filler0.*"))
    }

    @Test
    fun invalidRegexFallsBackToLiteralMatching() {
        val matched = planner.matchingKeys(listOf("/([/"), "literal /([/ inside")
        assertEquals(1, matched.size)
    }

    @Test
    fun concurrentLookupsDoNotCorruptTheCache() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { worker ->
            pool.execute {
                start.await()
                repeat(200) { index ->
                    planner.matchingKeys(listOf("/shared$worker-$index/"), "shared$worker-$index")
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(WorldbookPlanner.regexCacheSizeForTest() <= WorldbookPlanner.MAX_REGEX_CACHE_ENTRIES_FOR_TEST)
    }
}
