package com.instinctazero.android

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Position graph for the opening book. Source-path annotations describe occurrences,
 * not the route the player must take to reach a position. Queries remain index-backed. */
internal class RepertoirePositionBook(private val db: SQLiteDatabase, private val rep: String, private val local: JSONObject) {
    companion object {
        fun edgeKey(before: String, uci: String) = RepertoireStore.hash(
            "repertoire-edge-v1\n${RepertoireStore.position(before)}\n$uci".toByteArray()).take(32)
    }
    data class Node(val id: Long, val parent: Long, val path: String, val uci: String, val san: String,
        val fen: String, val before: String, val theory: Boolean, val optional: Boolean, val kind: String,
        val reason: String, val comment: String, val introduction: String, val added: Boolean = false, val sourceOrder: Int = 0)
    data class Flags(val active: Boolean, val optional: Boolean = false)
    data class Edge(val uci: String, val san: String, val fen: String, val before: String,
        val active: Boolean, val optional: Boolean, val kind: String, val reason: String,
        val nodes: List<Node>, val edited: Boolean)
    data class Addition(val before: String, val fen: String, val uci: String, val san: String)
    private val nodesById = mutableMapOf<Long,Node>()
    private val positions = mutableMapOf<String,List<Node>>()
    private val children = mutableMapOf<String,List<Node>>()
    private val paths = mutableMapOf<String,List<Node>>()
    private val inherited = mutableMapOf<Long,Flags>()
    private val edgeCache = mutableMapOf<String,List<Edge>>()
    private val activeCache = mutableMapOf<String,Boolean>()
    private val hasLabels = local.keys().asSequence().any { local.getJSONObject(it).optString("kind") in listOf("analysis","alternative") }
    private val columns = db.rawQuery("PRAGMA table_info(nodes)",null).use { rows ->
        buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
    }
    private val intro = if ("starting_comment" in columns) "n.starting_comment" else "''"
    private val sourceOrder = if ("srs" in columns) "n.srs" else "0"
    private val select = "SELECT n.id,n.parent_id,n.path_id,n.uci,n.san,n.fen,p.fen,n.theory,n.line_alternative,n.kind,n.reason,n.comment,$intro,$sourceOrder " +
        "FROM nodes n LEFT JOIN nodes p ON p.id=n.parent_id WHERE n.repertoire_id=? AND "
    private fun read(where: String, args: Array<String>): List<Node> = readQuery(select+where,arrayOf(rep,*args))
    private fun readQuery(sql: String, args: Array<String>): List<Node> = db.rawQuery(sql,args).use { rows ->
        buildList { while (rows.moveToNext()) {
            val node = Node(rows.getLong(0),if(rows.isNull(1))0 else rows.getLong(1),rows.getString(2),rows.getString(3) ?: "",
                rows.getString(4) ?: "",rows.getString(5),rows.getString(6) ?: "",rows.getInt(7)==1,rows.getInt(8)==1,
                rows.getString(9),rows.getString(10) ?: "",rows.getString(11) ?: "",rows.getString(12) ?: "",sourceOrder=rows.getInt(13))
            nodesById[node.id] = node; add(node)
        } }
    }
    private fun at(fen: String) = positions.getOrPut(fen) { read("n.fen=?",arrayOf(fen)) }
    private fun path(key: String) = paths.getOrPut(key) { read("n.path_id=?",arrayOf(key)) }
    private fun node(id: Long) = nodesById[id] ?: read("n.id=?",arrayOf(id.toString())).firstOrNull()
    internal fun childQuery(fen: String, uci: String? = null): Pair<String,Array<String>> {
        // The parent subquery makes SQLite scan the repertoire through nodes_training.
        // The installed index already stores the normalized pre-move FEN on every edge.
        // Preserve the occurrence order of the old nodes_training scan: it also sets
        // stable move/comment ordering and the fallback reason for duplicate edges.
        // Sort only the matched rows here: SQL ORDER BY srs would select the slow index again.
        val condition = if("fen_before" in columns) "n.fen_before=?" else "n.parent_id IN (SELECT id FROM nodes WHERE repertoire_id=? AND fen=?)"
        val args = if("fen_before" in columns) arrayOf(fen) else arrayOf(rep,fen)
        return (select + condition + if(uci==null) "" else " AND n.uci=?") to if(uci==null) arrayOf(rep,*args) else arrayOf(rep,*args,uci)
    }
    private fun readChildren(fen: String, uci: String? = null): List<Node> {
        val (sql,args) = childQuery(fen,uci)
        val nodes = readQuery(sql,args)
        return if("fen_before" in columns) nodes.sortedWith(compareBy<Node> { it.sourceOrder }.thenBy { it.id }) else nodes
    }
    private fun sourceChildren(fen: String) = children.getOrPut(fen) { readChildren(fen) }
    private fun positionEdit(n: Node): JSONObject? = local.optJSONObject(edgeKey(n.before,n.uci))?.takeIf { it.optString("scope") == "position" }
    // Existing path-scoped exclusions and optional labels still mask their source subtree.
    // A separately active occurrence at the same board position can nevertheless supply book moves.
    private fun flags(n: Node): Flags {
        if (!hasLabels) return Flags(n.theory,n.optional)
        val chain = mutableListOf<Node>(); val seen = mutableSetOf<Long>(); var current: Node? = n
        while (current != null && !inherited.containsKey(current.id)) {
            if (!seen.add(current.id) || chain.size > 512) return Flags(false)
            chain.add(current); current = if(current.parent==0L)null else node(current.parent)
        }
        var state = current?.let { inherited[it.id] } ?: Flags(true)
        for (entry in chain.asReversed()) {
            val edits = listOfNotNull(local.optJSONObject(entry.path),positionEdit(entry))
            state = Flags(state.active && edits.none { it.optString("kind")=="analysis" },
                state.optional || edits.any { it.optString("kind")=="alternative" })
            inherited[entry.id] = state
        }
        return Flags(n.theory && state.active,n.optional || state.optional)
    }
    private val localNodes: List<Node> by lazy {
        local.keys().asSequence().mapNotNull { key ->
            val edit = local.getJSONObject(key)
            if (!edit.optBoolean("added")) return@mapNotNull null
            val before = if(edit.optString("scope")=="position") edit.optString("before") else {
                val parent = edit.optString("parent")
                local.optJSONObject(parent)?.optString("fen")?.takeIf { it.isNotBlank() } ?: path(parent).firstOrNull()?.fen.orEmpty()
            }
            Node(0,0,key,edit.optString("uci"),edit.optString("san"),RepertoireStore.position(edit.optString("fen")),
                RepertoireStore.position(before),true,false,edit.optString("kind"),"Added on this phone.","","",true)
        }.toList()
    }
    private fun legacyFlags(n: Node): Flags {
        var key = n.path; var optional = false; val seen = mutableSetOf<String>()
        repeat(513) {
            if (!seen.add(key)) return Flags(false)
            val edit = local.optJSONObject(key)
            if (edit?.optString("kind")=="analysis") return Flags(false)
            optional = optional || edit?.optString("kind")=="alternative"
            if (edit?.optBoolean("added")==true && edit.optString("scope")!="position") {
                val entry = localNodes.firstOrNull { it.path==key } ?: return Flags(false)
                val global = positionEdit(entry)
                if (global?.optString("kind")=="analysis") return Flags(false)
                optional = optional || global?.optString("kind")=="alternative"
                key = edit.optString("parent")
            } else {
                val active = path(key).map(::flags).filter { it.active }
                return Flags(active.isNotEmpty(),optional || active.all { it.optional })
            }
        }
        return Flags(false)
    }
    private val localFlags: Map<String,Flags> by lazy {
        val result = mutableMapOf<String,Flags>()
        val reachable = mutableMapOf<String,Boolean>(); val queue = ArrayDeque<String>()
        fun reach(fen: String, optional: Boolean) {
            val old = reachable[fen]
            if (old == null || (old && !optional)) { reachable[fen] = optional; queue.addLast(fen) }
        }
        for (n in localNodes.filter { local.getJSONObject(it.path).optString("scope")!="position" }) {
            val state = legacyFlags(n); result[n.path] = state
            if (state.active) reach(n.fen,state.optional)
        }
        val edges = localNodes.filter { local.getJSONObject(it.path).optString("scope")=="position" }.groupBy { it.before }
        for (before in edges.keys) {
            val active = at(before).map(::flags).filter { it.active }
            if (active.isNotEmpty()) reach(before,active.all { it.optional })
        }
        while (queue.isNotEmpty()) {
            val before = queue.removeFirst()
            for (n in edges[before].orEmpty()) if(n.kind!="analysis") {
                val optional = reachable.getValue(before) || n.kind=="alternative"
                result[n.path] = Flags(true,optional); reach(n.fen,optional)
            }
        }
        result
    }
    private fun effective(n: Node) = if(n.added)localFlags[n.path] ?: Flags(false) else flags(n)
    private fun allAt(fen: String) = at(fen) + localNodes.filter { it.fen==fen }
    fun active(fen: String) = activeCache.getOrPut(fen) {
        // Departure/extension checks only need existence. Do not load hundreds of
        // duplicate occurrence comments at every historical position in an unedited book.
        if(local.length()==0) db.rawQuery("SELECT 1 FROM nodes WHERE repertoire_id=? AND fen=? AND theory=1 LIMIT 1",arrayOf(rep,fen)).use { it.moveToFirst() }
        else allAt(fen).any { effective(it).active }
    }
    private fun mergeEdges(fen: String, nodes: List<Node>): List<Edge> =
        nodes.filter { it.uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }
            .groupBy { it.uci }.map { (uci,nodes) ->
                val eligible = nodes.filter { effective(it).active }
                val best = eligible.firstOrNull { !effective(it).optional } ?: eligible.firstOrNull() ?: nodes.first()
                val edit = local.optJSONObject(edgeKey(fen,uci))
                Edge(uci,best.san,best.fen,fen,eligible.isNotEmpty(),eligible.isNotEmpty() && eligible.all { effective(it).optional },
                    if(eligible.isNotEmpty())"repertoire" else best.kind.takeUnless { it in listOf("repertoire","alternative") } ?: "analysis",
                    if(edit?.optString("kind")=="analysis")"Excluded on this phone. The original remains unchanged." else best.reason,
                    nodes,edit!=null || nodes.any { local.has(it.path) })
            }
    fun edges(fen: String): List<Edge> = edgeCache.getOrPut(fen) {
        mergeEdges(fen,sourceChildren(fen) + localNodes.filter { it.before==fen })
    }
    private fun edge(fen: String, uci: String): Edge? {
        edgeCache[fen]?.let { return it.find { e -> e.uci==uci } }
        // Extension validation needs just the played edge, not all alternative source
        // occurrences/comments at each earlier position (often the initial position).
        val source = children[fen]?.filter { it.uci==uci } ?: readChildren(fen,uci)
        return mergeEdges(fen,source + localNodes.filter { it.before==fen && it.uci==uci }).firstOrNull()
    }
    private fun comments(nodes: List<Node>, introduction: Boolean = false) = JSONArray(nodes.map { if(introduction)it.introduction else it.comment }.filter { it.isNotBlank() }.distinct())
    fun status(fen: String): JSONObject {
        val nodes = allAt(fen); val eligible = nodes.filter { effective(it).active }
        val optional = eligible.isNotEmpty() && eligible.all { effective(it).optional }
        val best = eligible.firstOrNull { !effective(it).optional } ?: eligible.firstOrNull() ?: nodes.firstOrNull()
        return JSONObject().put("fen",fen).put("known",nodes.isNotEmpty()).put("theory",eligible.isNotEmpty()).put("alternative",optional)
            .put("kind",if(eligible.isNotEmpty())"repertoire" else best?.kind ?: "unknown")
            .put("reason",best?.reason ?: "Outside this repertoire.").put("deviation",0)
            .put("end_of_line",eligible.isNotEmpty() && edges(fen).none { it.active })
            .put("comments",comments(nodes)).put("starting_comments",comments(nodes,true))
    }
    fun moveJson(edge: Edge, side: String): JSONObject {
        val own = edge.before.split(' ').getOrNull(1) == if(side=="white")"w" else "b"
        val target = status(edge.fen)
        return JSONObject().put("uci",edge.uci).put("san",edge.san).put("fen",edge.fen).put("theory",edge.active)
            .put("alternative",edge.optional).put("own",own).put("kind",if(edge.active && edge.optional && own)"alternative" else edge.kind)
            .put("reason",edge.reason).put("edited",edge.edited).put("deviation",0)
            .put("comments",comments(edge.nodes)).put("starting_comments",comments(edge.nodes,true))
            .put("position",target).put("end_of_line",target.getBoolean("end_of_line"))
    }
    /** Extend from the most recent covered position, not from an unrelated earlier move order. */
    fun additions(positions: List<String>, moves: List<String>, entries: JSONArray, anchor: Int = positions.indexOfLast(::active)): List<Addition>? {
        if (positions.size != moves.size+1 || entries.length()!=moves.size) return null
        if (anchor < 0) return null
        val additions = linkedMapOf<String,Addition>()
        for (ply in anchor until moves.size) {
            val edge = edge(positions[ply],moves[ply])
            // Re-adding a missing local prefix may reconnect its saved descendants. This
            // does not authorize an explicit exclusion or an informational source edge.
            if (edge!=null && !edge.active && !edge.nodes.all { it.added && it.kind!="analysis" }) return null
            if (edge==null) {
                val entry = entries.getJSONObject(ply)
                additions[edgeKey(positions[ply],moves[ply])] = Addition(positions[ply],positions[ply+1],moves[ply],entry.getString("san").take(16))
            }
        }
        return additions.values.toList()
    }
}
