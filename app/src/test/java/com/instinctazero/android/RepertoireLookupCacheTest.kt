package com.instinctazero.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class RepertoireLookupCacheTest {
    @Test fun leastRecentlyUsedEntriesHaveACountBoundAndSelectionsStaySeparate() {
        val cache = RepertoireLookupCache(maxEntries=2)
        cache.put("white","fen",JSONObject().put("theory",true))
        cache.put("black","fen",JSONObject().put("theory",false))
        assertTrue(cache.get("white","fen")!!.getBoolean("theory"))
        cache.put("white","other",JSONObject())
        assertNull(cache.get("black","fen")); assertNotNull(cache.get("white","fen"))
    }
    @Test fun byteBoundSkipsLargeCommentsWithoutTruncatingOrEvictingUnrelatedContent() {
        val cache = RepertoireLookupCache(maxBytes=1000)
        val text = "Long complete note. ".repeat(100)
        val large = JSONObject().put("comments",text)
        cache.put("r","first",JSONObject().put("note","a".repeat(150)))
        cache.put("r","second",JSONObject().put("note","b".repeat(150)))
        cache.put("r","third",JSONObject().put("note","c".repeat(150)))
        assertNull(cache.get("r","first")); assertNotNull(cache.get("r","third"))
        cache.put("r","oversized",large)
        assertNull(cache.get("r","oversized")); assertEquals(text,large.getString("comments"))
        assertNotNull(cache.get("r","third"))
    }
    @Test fun mutableCallersCannotChangeSharedPositionOrChildFactsAndClearIsComplete() {
        val cache = RepertoireLookupCache()
        val original = JSONObject().put("child",JSONObject().put("theory",true))
        cache.put("r","fen",original)
        original.getJSONObject("child").put("theory",false)
        val copy = cache.get("r","fen")!!
        assertTrue(copy.getJSONObject("child").getBoolean("theory"))
        copy.put("deviation",19); copy.getJSONObject("child").put("theory",false)
        assertFalse(cache.get("r","fen")!!.has("deviation"))
        assertTrue(cache.get("r","fen")!!.getJSONObject("child").getBoolean("theory"))
        cache.clear(); assertNull(cache.get("r","fen"))
    }
}
