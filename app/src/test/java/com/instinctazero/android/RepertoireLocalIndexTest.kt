package com.instinctazero.android

import com.instinctazero.android.RepertoirePositionBook.Flags
import com.instinctazero.android.RepertoirePositionBook.Node
import org.junit.Assert.*
import org.junit.Test

class RepertoireLocalIndexTest {
    private fun index() = RepertoireLocalIndex(listOf(
        Node(0,0,"path","e2e4","e4","after","before",true,false,"repertoire","","","",true)
    ),false)
    @Test fun indexesShareImmutableNodesAndRetainOnlyCompletedFlags() {
        val index=index()
        assertSame(index.nodes.single(),index.byBefore.getValue("before").single())
        assertSame(index.nodes.single(),index.byFen.getValue("after").single())
        assertSame(index.nodes.single(),index.byPath.getValue("path"))
        assertNull(index.flags)
        index.flags=mapOf("path" to Flags(true))
        val cache=RepertoireLocalIndexCache();cache.put("book",index)
        assertSame(index,cache.get("book"));assertEquals(Flags(true),cache.get("book")!!.flags!!["path"])
        cache.clear();assertNull(cache.get("book"))
    }
    @Test fun byteAndEntryLimitsAndLeastRecentlyUsedEvictionAreEnforced() {
        val index=index()
        val small=RepertoireLocalIndexCache(maxBytes=index.bytes);small.put("book",index)
        assertNull(small.get("book")) // Account for the cache key/entry as well as its value.
        val cache=RepertoireLocalIndexCache(maxEntries=2)
        cache.put("a",index);cache.put("b",index);cache.get("a");cache.put("c",index)
        assertNull(cache.get("b"));assertNotNull(cache.get("a"));assertNotNull(cache.get("c"))
        repeat(20) { cache.put("a",index) }
        assertNotNull(cache.get("c")) // Replacements must not leak byte accounting.
        val disabled=RepertoireLocalIndexCache(maxEntries=0);disabled.put("a",index);assertNull(disabled.get("a"))
    }
}
