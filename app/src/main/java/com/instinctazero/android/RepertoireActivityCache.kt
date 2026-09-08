package com.instinctazero.android

/** Position facts, never departure/extension state. Cleared with the store's edit revision. */
internal class RepertoireActivityCache(private val maxBytes: Int = 512 * 1024, private val maxEntries: Int = 2048) {
    enum class Transition { MISSING, ACTIVE, RECONNECTABLE, BLOCKED }
    private val entries = LinkedHashMap<String,Int>(16,.75f,true)
    private var bytes = 0
    private fun key(rep: String, fen: String) = "p\n$rep\n$fen"
    private fun size(key: String) = 96 + key.length * 2
    fun get(rep: String, fen: String): Boolean? = entries[key(rep,fen)]?.let { it==1 }
    fun put(rep: String, fen: String, active: Boolean) {
        putState(key(rep,fen),if(active)1 else 0)
    }
    fun transition(rep: String, fen: String, uci: String): Transition? = entries["e\n$rep\n$fen\n$uci"]?.let { Transition.entries[it] }
    fun putTransition(rep: String, fen: String, uci: String, state: Transition) = putState("e\n$rep\n$fen\n$uci",state.ordinal)
    private fun putState(key: String, value: Int) {
        if(size(key)>maxBytes)return
        if(!entries.containsKey(key))bytes+=size(key)
        entries[key]=value
        val oldest=entries.entries.iterator()
        while(bytes>maxBytes || entries.size>maxEntries) {
            val entry=oldest.next();bytes-=size(entry.key);oldest.remove()
        }
    }
    fun clear() { entries.clear();bytes=0 }
}
