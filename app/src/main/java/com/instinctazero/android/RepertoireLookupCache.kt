package com.instinctazero.android

import org.json.JSONObject

/** Only position facts, never history-dependent deviation/addition state. Owned by the
 * synchronized store. Serialized values prevent callers mutating a cached child status. */
internal class RepertoireLookupCache(private val maxBytes: Int = 4 * 1024 * 1024, private val maxEntries: Int = 384) {
    private val entries = LinkedHashMap<String,String>(16,0.75f,true)
    private var bytes = 0
    private fun key(rep: String, fen: String) = "$rep\n$fen"
    private fun size(key: String, value: String) = 128 + (key.length + value.length) * 2
    fun get(rep: String, fen: String): JSONObject? = entries[key(rep,fen)]?.let(::JSONObject)
    fun put(rep: String, fen: String, value: JSONObject) {
        val key = key(rep,fen); val raw = value.toString()
        entries.remove(key)?.let { bytes -= size(key,it) }
        // Oversized comments still reach the caller in full; they just bypass the cache.
        if(size(key,raw)>maxBytes) return
        entries[key] = raw; bytes += size(key,raw)
        val oldest = entries.entries.iterator()
        while(bytes>maxBytes || entries.size>maxEntries) {
            val entry = oldest.next(); bytes -= size(entry.key,entry.value); oldest.remove()
        }
    }
    fun clear() { entries.clear(); bytes = 0 }
}
