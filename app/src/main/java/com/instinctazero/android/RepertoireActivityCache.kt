package com.instinctazero.android

/** Position facts, never departure/extension state. Cleared with the store's edit revision. */
internal class RepertoireActivityCache(private val maxBytes: Int = 512 * 1024, private val maxEntries: Int = 2048) {
    enum class Transition { MISSING, ACTIVE, RECONNECTABLE, INFORMATIONAL, BLOCKED }
    private val entries = LinkedHashMap<String,Int>(16,.75f,true)
    private var bytes = 0
    // Separate budget prevents a long game's coverage checks from evicting all its
    // one-ply choices (and vice versa). Neither cache stores history or comments.
    private val choices = LinkedHashMap<String,List<String>>(16,.75f,true)
    private var choiceBytes = 0
    private fun key(rep: String, fen: String) = "p\n$rep\n$fen"
    private fun size(key: String) = 96 + key.length * 2
    fun get(rep: String, fen: String): Boolean? = entries[key(rep,fen)]?.let { it==1 }
    fun put(rep: String, fen: String, active: Boolean) {
        putState(key(rep,fen),if(active)1 else 0)
    }
    fun continuation(rep: String, fen: String): Boolean? = entries["c\n$rep\n$fen"]?.let { it==1 }
    fun putContinuation(rep: String, fen: String, value: Boolean) = putState("c\n$rep\n$fen",if(value)1 else 0)
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
    fun choices(rep: String, fen: String): List<String>? = choices[key(rep,fen)]
    private fun choiceSize(key: String,value: List<String>) = size(key)+32+value.sumOf { 40+it.length*2 }
    fun putChoices(rep: String,fen: String,value: List<String>) {
        val key=key(rep,fen);val size=choiceSize(key,value)
        if(size>maxBytes)return
        choices.remove(key)?.let { choiceBytes-=choiceSize(key,it) }
        choices[key]=value.toList();choiceBytes+=size
        val oldest=choices.entries.iterator()
        while(choiceBytes>maxBytes || choices.size>maxEntries) {
            val entry=oldest.next();choiceBytes-=choiceSize(entry.key,entry.value);oldest.remove()
        }
    }
    fun clear() { entries.clear();bytes=0;choices.clear();choiceBytes=0 }
}
