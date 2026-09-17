package com.instinctazero.android

import com.instinctazero.android.RepertoirePositionBook.Flags
import com.instinctazero.android.RepertoirePositionBook.Node

/** Revision-scoped read model. It owns no database, request, cancellation signal or JSON edits.
 * Only a completely resolved reachability map is published; cancellation leaves it retryable. */
internal class RepertoireLocalIndex(val nodes: List<Node>, val hasMasks: Boolean) {
    val byBefore = nodes.groupBy { it.before }
    val byFen = nodes.groupBy { it.fen }
    val byPath = nodes.associateBy { it.path }
    var flags: Map<String, Flags>? = null
    // Includes a conservative allowance for all three indexes and the eventual flags map.
    val bytes: Long = 256L + nodes.sumOf { node ->
        768L + 2L * (node.path.length + node.before.length + node.fen.length + node.uci.length + node.san.length + node.kind.length)
    }
}

/** Owned by the synchronized store, invalidated together with every other derived book fact. */
internal class RepertoireLocalIndexCache(private val maxBytes: Long = 2L * 1024 * 1024, private val maxEntries: Int = 16) {
    init { require(maxBytes >= 0 && maxEntries >= 0) }
    private val entries = LinkedHashMap<String, RepertoireLocalIndex>(16, .75f, true)
    private var bytes = 0L
    fun get(rep: String) = entries[rep]
    fun put(rep: String, index: RepertoireLocalIndex) {
        entries.remove(rep)?.let { bytes -= size(rep, it) }
        val size = size(rep, index)
        if(size > maxBytes || maxEntries == 0) return
        entries[rep] = index; bytes += size
        val oldest = entries.entries.iterator()
        while(bytes > maxBytes || entries.size > maxEntries) {
            val entry = oldest.next(); bytes -= size(entry.key, entry.value); oldest.remove()
        }
    }
    private fun size(rep: String, index: RepertoireLocalIndex) = 96L + rep.length * 2L + index.bytes
    fun clear() { entries.clear(); bytes = 0 }
}
