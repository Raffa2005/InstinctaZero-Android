package com.instinctazero.android
import org.junit.Assert.*
import org.junit.Test
class RepertoireActivityCacheTest {
    @Test fun choicesAreBoundedIndependentAndClearedWithTheEditRevision() {
        val cache=RepertoireActivityCache(maxBytes=500,maxEntries=2)
        cache.putChoices("r","fen",listOf("a2a3"));cache.putChoices("s","fen",emptyList())
        assertEquals(listOf("a2a3"),cache.choices("r","fen"));assertEquals(emptyList<String>(),cache.choices("s","fen"))
        cache.putChoices("t","fen",listOf("b2b3"));assertNull(cache.choices("r","fen"))
        cache.putChoices("t","fen",listOf("c2c3"));assertEquals(listOf("c2c3"),cache.choices("t","fen"))
        cache.putChoices("r","huge".repeat(100),listOf("a2a3"));assertNull(cache.choices("r","huge".repeat(100)))
        cache.clear();assertNull(cache.choices("t","fen"))
    }
    @Test fun falseFactsStayDistinctFromMissesAndOtherRepertoires() {
        val cache=RepertoireActivityCache(maxEntries=2)
        cache.put("r","a",false);cache.put("s","a",true)
        assertEquals(false,cache.get("r","a"));assertEquals(true,cache.get("s","a"))
        cache.get("r","a");cache.put("r","b",true)
        assertNull(cache.get("s","a"));assertEquals(false,cache.get("r","a"))
        cache.clear();assertNull(cache.get("r","a"))
    }
    @Test fun bytesAndEntryCountStayBounded() {
        val cache=RepertoireActivityCache(maxBytes=230,maxEntries=10)
        cache.put("r","a",true);cache.put("r","b",false);cache.put("r","c",true)
        assertNull(cache.get("r","a"));assertEquals(false,cache.get("r","b"))
        cache.put("r","huge".repeat(100),true);assertNull(cache.get("r","huge".repeat(100)))
        assertEquals(true,cache.get("r","c"))
    }
    @Test fun transitionFactsCannotCollideWithMembershipAndClearOnRevision() {
        val cache=RepertoireActivityCache()
        cache.put("r","fen",false)
        cache.putTransition("r","fen","e2e4",RepertoireActivityCache.Transition.RECONNECTABLE)
        assertEquals(false,cache.get("r","fen"))
        assertEquals(RepertoireActivityCache.Transition.RECONNECTABLE,cache.transition("r","fen","e2e4"))
        assertNull(cache.transition("s","fen","e2e4"));assertNull(cache.transition("r","fen","d2d4"))
        cache.clear();assertNull(cache.get("r","fen"));assertNull(cache.transition("r","fen","e2e4"))
    }
}
