package com.instinctazero.android

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.instinctazero.android.RepertoireActivityCache.Transition
import org.json.JSONArray
import org.json.JSONObject

/** Position graph for the opening book. Source-path annotations describe occurrences,
 * not the route the player must take to reach a position. Queries remain index-backed. */
internal class RepertoirePositionBook(private val db: SQLiteDatabase?, private val rep: String, private val local: JSONObject, private val localRoot: String? = null,
    private val cancellation: CancellationSignal? = null, private val sharedActivity: RepertoireActivityCache? = null, sourceColumns: Set<String>? = null, private val sharedLocal: RepertoireLocalIndexCache? = null) {
    companion object {
        private val uciPattern = Regex("[a-h][1-8][a-h][1-8][qrbn]?")
        fun readColumns(db: SQLiteDatabase, cancellation: CancellationSignal? = null): Set<String> =
            db.rawQuery("PRAGMA table_info(nodes)", null, cancellation).use { rows ->
                buildSet { while (rows.moveToNext()) { cancellation?.throwIfCanceled(); add(rows.getString(1)) } }
            }
        fun edgeKey(before: String, uci: String) = RepertoireStore.hash(
            "repertoire-edge-v1\n${RepertoireStore.position(before)}\n$uci".toByteArray()).take(32)
        fun commentKey(fen: String) = RepertoireStore.hash("repertoire-comment-v1\n${RepertoireStore.position(fen)}".toByteArray()).take(32)
    }
    data class Node(val id: Long, val parent: Long, val path: String, val uci: String, val san: String,
        val fen: String, val before: String, val theory: Boolean, val optional: Boolean, val kind: String,
        val reason: String, val comment: String, val introduction: String, val added: Boolean = false, val sourceOrder: Int = 0)
    data class Flags(val active: Boolean, val optional: Boolean = false)
    data class Edge(val uci: String, val san: String, val fen: String, val before: String,
        val active: Boolean, val optional: Boolean, val kind: String, val reason: String,
        val nodes: List<Node>, val edited: Boolean, val reentry: Boolean = false)
    data class Addition(val before: String, val fen: String, val uci: String, val san: String, val anchored: Boolean = false, val ply: Int = 0)
    private val nodesById = mutableMapOf<Long,Node>()
    private val positions = mutableMapOf<String,List<Node>>()
    private val children = mutableMapOf<String,List<Node>>()
    private val paths = mutableMapOf<String,List<Node>>()
    private val inherited = mutableMapOf<Long,Flags>()
    private val edgeCache = mutableMapOf<String,List<Edge>>()
    private val candidateCache = mutableMapOf<String,List<Edge>>()
    private val activeCache = mutableMapOf<String,Boolean>()
    private val edgeEdits = mutableMapOf<Pair<String,String>,JSONObject?>()
    private val transitions = mutableMapOf<Pair<String,String>,Transition>()
    // Choosing a main recommendation does not mask ancestry or change membership.
    private val hasLabels get() = localIndex.hasMasks
    private val columns = sourceColumns ?: db?.let { readColumns(it, cancellation) } ?: emptySet()
    private val intro = if ("starting_comment" in columns) "n.starting_comment" else "''"
    private val sourceOrder = if ("srs" in columns) "n.srs" else "0"
    private val select = "SELECT n.id,n.parent_id,n.path_id,n.uci,n.san,n.fen,p.fen,n.theory,n.line_alternative,n.kind,n.reason,n.comment,$intro,$sourceOrder " +
        "FROM nodes n LEFT JOIN nodes p ON p.id=n.parent_id WHERE n.repertoire_id=? AND "
    private fun read(where: String, args: Array<String>): List<Node> = readQuery(select+where,arrayOf(rep,*args))
    private fun facts(where: String,args: Array<String>): List<Node> = readQuery((select+where).replace("n.comment,$intro","'', ''"),arrayOf(rep,*args))
    private fun factsAt(fen: String) = facts("n.fen=?",arrayOf(fen)) + if(localRoot==fen) listOf(Node(0,0,"","","",fen,"",true,false,"repertoire","","","")) else emptyList()
    private fun factsChildren(fen: String): List<Node> {
        val (sql,args)=childQuery(fen)
        return readQuery(sql.replace("n.comment,$intro","'', ''"),args)+localByBefore[fen].orEmpty()
    }
    private fun readQuery(sql: String, args: Array<String>): List<Node> = db?.rawQuery(sql,args,cancellation)?.use { rows ->
        buildList { while (rows.moveToNext()) {
            cancellation?.throwIfCanceled()
            val node = Node(rows.getLong(0),if(rows.isNull(1))0 else rows.getLong(1),rows.getString(2),rows.getString(3) ?: "",
                rows.getString(4) ?: "",rows.getString(5),rows.getString(6) ?: "",rows.getInt(7)==1,rows.getInt(8)==1,
                rows.getString(9),rows.getString(10) ?: "",rows.getString(11) ?: "",rows.getString(12) ?: "",sourceOrder=rows.getInt(13))
            nodesById[node.id] = node; add(node)
        } }
    } ?: emptyList()
    private fun at(fen: String) = positions.getOrPut(fen) {
        read("n.fen=?",arrayOf(fen)) + if(localRoot==fen) listOf(Node(0,0,"","","",fen,"",true,false,"repertoire","Created on this phone.","","")) else emptyList()
    }
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
    private fun edgeEdit(before: String, uci: String): JSONObject? {
        val key = before to uci
        // Cache absent edits too: getOrPut would rehash every miss.
        if (!edgeEdits.containsKey(key)) edgeEdits[key] = local.optJSONObject(edgeKey(before,uci))
        return edgeEdits[key]
    }
    private fun positionEdit(n: Node) = edgeEdit(n.before,n.uci)?.takeIf { it.optString("scope") == "position" }
    /** Batch the same ancestors flags() already visits; retain occurrence-scoped semantics. */
    private fun prepareFlags(nodes: List<Node>) {
        if(!hasLabels)return
        var frontier=nodes
        val visited=mutableSetOf<Long>()
        while(frontier.isNotEmpty()) {
            cancellation?.throwIfCanceled()
            val parents=frontier.map { it.parent }.filter { it!=0L && it !in inherited && visited.add(it) }
            parents.filter { it !in nodesById }.chunked(400).forEach { chunk ->
                val (sql,args)=ancestorQuery(chunk)
                readQuery(sql,args)
            }
            frontier=parents.mapNotNull { nodesById[it] }
        }
    }
    /** IDs are the INTEGER PRIMARY KEY. Disallow only secondary indexes here: the
     * repertoire/srs index otherwise turns a 400-ID batch into a whole-book scan. */
    internal fun ancestorQuery(ids: List<Long>): Pair<String,Array<String>> {
        require(ids.isNotEmpty() && ids.size<=400)
        return (select.replace("FROM nodes n LEFT JOIN", "FROM nodes n NOT INDEXED LEFT JOIN")
            .replace("n.comment,$intro", "'', ''") + "n.id IN (${ids.joinToString(",") { "?" }})") to
            arrayOf(rep,*ids.map(Long::toString).toTypedArray())
    }
    // Existing path-scoped exclusions and optional labels still mask their source subtree.
    // A separately active occurrence at the same board position can nevertheless supply book moves.
    private fun flags(n: Node): Flags {
        if (!hasLabels) return Flags(n.theory,n.optional)
        val chain = mutableListOf<Node>(); val seen = mutableSetOf<Long>(); var current: Node? = n
        while (current != null && !inherited.containsKey(current.id)) {
            cancellation?.throwIfCanceled()
            if (!seen.add(current.id) || chain.size > 512) return Flags(false)
            chain.add(current); current = if(current.parent==0L)null else node(current.parent)
        }
        var state = current?.let { inherited[it.id] } ?: Flags(true)
        for (entry in chain.asReversed()) {
            val edits = listOfNotNull(local.optJSONObject(entry.path),positionEdit(entry))
            state = Flags(state.active && edits.none { it.optString("kind")=="analysis" || it.optBoolean("deleted") },
                state.optional || edits.any { it.optString("kind")=="alternative" })
            inherited[entry.id] = state
        }
        return Flags(n.theory && state.active,n.optional || state.optional)
    }
    /** Imported information is not a user exclusion. Check the local ancestry mask
     * independently of source theory, including legacy path-scoped exclusions. */
    private fun sourceAllowed(n: Node): Boolean {
        if(!hasLabels)return true
        flags(n)
        return inherited[n.id]?.active == true
    }
    private val localIndex: RepertoireLocalIndex by lazy {
        sharedLocal?.get(rep) ?: RepertoireLocalIndex(buildLocalNodes(), local.keys().asSequence().any {
            local.getJSONObject(it).let { e -> e.optBoolean("deleted") || e.optString("kind") in listOf("analysis","alternative") }
        }).also { cancellation?.throwIfCanceled(); sharedLocal?.put(rep,it) }
    }
    private val localNodes get() = localIndex.nodes
    private fun buildLocalNodes(): List<Node> =
        local.keys().asSequence().mapNotNull { key ->
            cancellation?.throwIfCanceled()
            val edit = local.getJSONObject(key)
            if (!edit.optBoolean("added")) return@mapNotNull null
            val before = if(edit.optString("scope")=="position") edit.optString("before") else {
                val parent = edit.optString("parent")
                local.optJSONObject(parent)?.optString("fen")?.takeIf { it.isNotBlank() } ?: path(parent).firstOrNull()?.fen.orEmpty()
            }
            Node(0,0,key,edit.optString("uci"),edit.optString("san"),RepertoireStore.position(edit.optString("fen")),
                RepertoireStore.position(before),true,false,edit.optString("kind"),"Added on this phone.","","",true)
        }.toList()
    private val localByBefore get() = localIndex.byBefore
    private val localByFen get() = localIndex.byFen
    private val localByPath get() = localIndex.byPath
    private fun legacyFlags(n: Node): Flags {
        var key = n.path; var optional = false; val seen = mutableSetOf<String>()
        repeat(513) {
            if (!seen.add(key)) return Flags(false)
            val edit = local.optJSONObject(key)
            if (edit?.optString("kind")=="analysis" || edit?.optBoolean("deleted")==true) return Flags(false)
            optional = optional || edit?.optString("kind")=="alternative"
            if (edit?.optBoolean("added")==true && edit.optString("scope")!="position") {
                val entry = localByPath[key] ?: return Flags(false)
                val global = positionEdit(entry)
                if (global?.optString("kind")=="analysis" || global?.optBoolean("deleted")==true) return Flags(false)
                optional = optional || global?.optString("kind")=="alternative"
                key = edit.optString("parent")
            } else {
                val source=path(key);prepareFlags(source)
                val active = source.map(::flags).filter { it.active }
                return Flags(active.isNotEmpty(),optional || active.all { it.optional })
            }
        }
        return Flags(false)
    }
    private val localFlags: Map<String,Flags> get() = localIndex.flags ?: resolveLocalFlags().also {
        cancellation?.throwIfCanceled()
        localIndex.flags = it
    }
    private fun resolveLocalFlags(): Map<String,Flags> {
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
        // An addition must not cause one query (and duplicate occurrence decoding)
        // per saved move on every marker, position, or history request.
        for (chunk in edges.keys.chunked(400)) {
            cancellation?.throwIfCanceled()
            val placeholders = chunk.joinToString(",") { "?" }
            if (!hasLabels) {
                db?.rawQuery("SELECT fen,MAX(theory),MIN(CASE WHEN theory=1 THEN line_alternative ELSE 1 END) " +
                    "FROM nodes WHERE repertoire_id=? AND fen IN ($placeholders) GROUP BY fen",arrayOf(rep,*chunk.toTypedArray()),cancellation)?.use { rows ->
                    while(rows.moveToNext()) {
                        cancellation?.throwIfCanceled()
                        val before=rows.getString(0)
                        if(rows.getInt(1)==1)reach(before,rows.getInt(2)==1)
                        if(edges.getValue(before).any { local.getJSONObject(it.path).optBoolean("anchored") })reach(before,false)
                    }
                }
            } else {
                val sources=facts("n.fen IN ($placeholders)",chunk.toTypedArray())
                prepareFlags(sources)
                for((before,source) in sources.groupBy { it.fen }) {
                    val active=source.map(::flags).filter { it.active }
                    if(active.isNotEmpty())reach(before,active.all { it.optional })
                    // Informational anchors still respect deliberate ancestry exclusions.
                    if(source.any(::sourceAllowed) && edges.getValue(before).any { local.getJSONObject(it.path).let { e ->
                        e.optBoolean("anchored") && !e.optBoolean("deleted") && e.optString("kind")!="analysis"
                    } })reach(before,false)
                }
            }
        }
        if(localRoot in edges)reach(localRoot!!,false)
        while (queue.isNotEmpty()) {
            cancellation?.throwIfCanceled()
            val before = queue.removeFirst()
            for (n in edges[before].orEmpty()) if(n.kind!="analysis" && !local.getJSONObject(n.path).optBoolean("deleted")) {
                val optional = reachable.getValue(before) || n.kind=="alternative"
                result[n.path] = Flags(true,optional); reach(n.fen,optional)
            }
        }
        return result
    }
    private fun effective(n: Node) = if(n.added)localFlags[n.path] ?: Flags(false) else flags(n)
    private fun allAt(fen: String) = at(fen) + localByFen[fen].orEmpty()
    fun active(fen: String): Boolean {
        cancellation?.throwIfCanceled()
        sharedActivity?.get(rep,fen)?.let { return it }
        return activeCache.getOrPut(fen) {
        // Departure/extension checks only need existence. Do not load hundreds of
        // duplicate occurrence comments at every historical position in an unedited book.
        if(!hasLabels) localRoot==fen || localByFen[fen].orEmpty().any { effective(it).active } ||
            db?.rawQuery("SELECT 1 FROM nodes WHERE repertoire_id=? AND fen=? AND theory=1 LIMIT 1",arrayOf(rep,fen),cancellation)?.use { it.moveToFirst() } == true
        else { val nodes=factsAt(fen)+localByFen[fen].orEmpty();prepareFlags(nodes);nodes.any { effective(it).active } }
        }.also { sharedActivity?.put(rep,fen,it) }
    }
    /** Source and added edges feed the same membership query. Ordinary additions do
     * not disable indexed source aggregation; only actual ancestry masks need occurrences. */
    private fun activePositions(targets: List<String>): Map<String,Boolean> {
        val result=mutableMapOf<String,Boolean>()
        if(targets.isEmpty())return result
        if(!hasLabels) {
            db?.rawQuery("SELECT fen,MIN(line_alternative) FROM nodes WHERE repertoire_id=? AND fen IN (${targets.joinToString(",") { "?" }}) AND theory=1 GROUP BY fen",arrayOf(rep,*targets.toTypedArray()),cancellation)?.use {
                while(it.moveToNext()) { cancellation?.throwIfCanceled();result[it.getString(0)]=it.getInt(1)==1 }
            }
        } else {
            val found=facts("n.fen IN (${targets.joinToString(",") { "?" }})",targets.toTypedArray())
            prepareFlags(found)
            for(n in found) {
                val state=flags(n)
                if(state.active)result[n.fen]=(result[n.fen] ?: true) && state.optional
            }
        }
        for(fen in targets) {
            cancellation?.throwIfCanceled()
            for(n in localByFen[fen].orEmpty()) {
                val state=effective(n)
                if(state.active)result[fen]=(result[fen] ?: true) && state.optional
            }
        }
        return result
    }
    /** Batch history membership for edited and unedited repertoires alike. */
    fun prepareHistory(history: List<String>) {
        val missing=history.distinct().filter { !activeCache.containsKey(it) && sharedActivity?.get(rep,it)==null }
        for(chunk in missing.chunked(400)) {
            cancellation?.throwIfCanceled()
            val active=activePositions(chunk)
            for(fen in chunk) { val value=fen==localRoot || fen in active;activeCache[fen]=value;sharedActivity?.put(rep,fen,value) }
        }
    }
    private fun mergeEdges(fen: String, nodes: List<Node>): List<Edge> =
        nodes.also { cancellation?.throwIfCanceled();prepareFlags(it) }.filter { it.uci.matches(uciPattern) }
            .groupBy { it.uci }.map { (uci,nodes) ->
                val eligible = nodes.filter { effective(it).active }
                val best = eligible.firstOrNull { !effective(it).optional } ?: eligible.firstOrNull() ?: nodes.first()
                val edit = edgeEdit(fen,uci)
                Edge(uci,best.san,best.fen,fen,eligible.isNotEmpty(),eligible.isNotEmpty() && eligible.all { effective(it).optional },
                    if(eligible.isNotEmpty())"repertoire" else best.kind.takeUnless { it in listOf("repertoire","alternative") } ?: "analysis",
                    if(edit?.optString("kind")=="analysis")"Excluded on this phone. The original remains unchanged." else best.reason,
                    nodes,edit!=null || nodes.any { local.has(it.path) })
            }
    /** Only absent associations may be inferred. An explicit informational source
     * edge stays informational even if its destination also occurs in theory. */
    private fun reentries(fen: String, recorded: List<Edge>): List<Edge> = candidateCache.getOrPut(fen) {
        val legal=RepertoireLegalMoves.from(fen).filter { move -> recorded.none { it.uci==move.uci } }
        if(legal.isEmpty())return@getOrPut emptyList()
        if(hasLabels) {
            val source=factsAt(fen);prepareFlags(source)
            if(source.isNotEmpty() && source.none(::sourceAllowed) && localByFen[fen].orEmpty().none { effective(it).active })return@getOrPut emptyList()
        }
        val targets=legal.map { it.fen }.distinct()
        val eligiblePositions=activePositions(targets)
        legal.mapNotNull { move ->
            cancellation?.throwIfCanceled()
            if(move.fen !in eligiblePositions && localRoot!=move.fen)return@mapNotNull null
            val edit=edgeEdit(fen,move.uci)
            val excluded=edit?.optBoolean("deleted")==true || edit?.optString("kind")=="analysis"
            Edge(move.uci,RepertoireLegalMoves.san(fen,move.uci),move.fen,fen,!excluded,
                edit?.optString("kind")=="alternative" || eligiblePositions[move.fen]==true,
                if(excluded)"analysis" else "repertoire",
                if(excluded)"Excluded on this phone. The original remains unchanged." else "Legal transposition into a covered repertoire position.",
                emptyList(),edit!=null,true)
        }
    }
    fun edges(fen: String): List<Edge> = edgeCache.getOrPut(fen) {
        val recorded=mergeEdges(fen,sourceChildren(fen) + localByBefore[fen].orEmpty())
        (recorded+reentries(fen,recorded)).also { sharedActivity?.putChoices(rep,fen,it.filter { e -> e.active && !deleted(e) }.map { e -> e.uci }) }
    }
    private fun hasContinuation(fen: String): Boolean {
        sharedActivity?.choices(rep,fen)?.let { return it.isNotEmpty() }
        sharedActivity?.continuation(rep,fen)?.let { return it }
        val found = localByBefore[fen].orEmpty().any { it.uci.matches(uciPattern) && effective(it).active && edgeEdit(it.before,it.uci)?.optBoolean("deleted")!=true } ||
            (!hasLabels && "fen_before" in columns &&
                db?.rawQuery("SELECT 1 FROM nodes WHERE repertoire_id=? AND fen_before=? AND theory=1 LIMIT 1",arrayOf(rep,fen),cancellation)?.use { it.moveToFirst() }==true) || run {
                val recorded=mergeEdges(fen,factsChildren(fen))
                recorded.any { it.active && !deleted(it) } || reentries(fen,recorded).any { it.active && !deleted(it) }
            }
        sharedActivity?.putContinuation(rep,fen,found)
        return found
    }
    fun deleted(edge: Edge) = edgeEdit(edge.before,edge.uci)?.optBoolean("deleted")==true
    fun recommendation(edge: Edge,side: String): String {
        if(!edge.active || deleted(edge))return "informational"
        if(edge.before.split(' ')[1] != if(side=="white")"w" else "b")return "reply"
        val candidates=edges(edge.before).filter { it.active && !deleted(it) }
        val chosen=candidates.firstOrNull { edgeEdit(it.before,it.uci)?.optString("kind")=="main" }
        if(chosen!=null)return if(chosen.uci==edge.uci)"main" else "alternative"
        if(candidates.size==1)return "main"
        if(edge.reentry)return "unassigned" // A position match is not a ranking of its incoming moves.
        if(candidates.none { !it.optional })return "unassigned"
        return if(edge.optional)"alternative" else "main"
    }
    /** Cheap first-stage UI facts; comments and full move details arrive separately. */
    fun marker(fen: String): JSONObject {
        val active=active(fen)
        val continuation=active && hasContinuation(fen)
        return JSONObject().put("fen",fen).put("theory",active).put("end_of_line",active && !continuation)
    }
    /** Nearest earlier position with another active book continuation, not a path ID. */
    fun intersection(history: List<String>,moves: List<String>): Int {
        if(history.size!=moves.size+1)return -1
        for(ply in moves.indices.reversed()) {
            cancellation?.throwIfCanceled()
            val fen=history[ply]
            val choices=sharedActivity?.choices(rep,fen) ?: run {
                val recorded=mergeEdges(fen,factsChildren(fen))
                (recorded+reentries(fen,recorded)).filter { it.active && !deleted(it) }.map { it.uci }
                    .also { sharedActivity?.putChoices(rep,fen,it) }
            }
            if(choices.any { it!=moves[ply] })return ply
        }
        return -1
    }
    private fun edge(fen: String, uci: String): Edge? {
        edgeCache[fen]?.let { return it.find { e -> e.uci==uci } }
        // Extension validation needs just the played edge, not all alternative source
        // occurrences/comments at each earlier position (often the initial position).
        val source = children[fen]?.filter { it.uci==uci } ?: readChildren(fen,uci)
        return mergeEdges(fen,source + localByBefore[fen].orEmpty().filter { it.uci==uci }).firstOrNull()
    }
    private fun transition(fen: String, uci: String): Transition = sharedActivity?.transition(rep,fen,uci) ?: transitions.getOrPut(fen to uci) {
        val edit=edgeEdit(fen,uci)
        val edge=edge(fen,uci)
        when {
            edit?.optBoolean("deleted")==true || edit?.optString("kind")=="analysis" -> Transition.BLOCKED
            edge==null -> Transition.MISSING
            deleted(edge) -> Transition.BLOCKED
            edge.active -> Transition.ACTIVE
            edge.nodes.any { !it.added && sourceAllowed(it) } -> Transition.INFORMATIONAL
            edge.nodes.all { it.added && it.kind!="analysis" } -> Transition.RECONNECTABLE
            else -> Transition.BLOCKED
        }.also { sharedActivity?.putTransition(rep,fen,uci,it) }
    }
    private fun prepareTransitions(positions: List<String>, moves: List<String>, anchor: Int) {
        if(hasLabels || (db!=null && "fen_before" !in columns))return
        val missing=(anchor until moves.size).map { positions[it] to moves[it] }.distinct().filter {
            it !in transitions && sharedActivity?.transition(rep,it.first,it.second)==null
        }
        for(chunk in missing.map { it.first }.distinct().chunked(400)) {
            cancellation?.throwIfCanceled()
            val states=mutableMapOf<Pair<String,String>,Transition>()
            // Only edge existence/activity is needed to validate an extension. Do not
            // read every source occurrence's SAN/comment for each past game move.
            db?.rawQuery("SELECT fen_before,uci,MAX(theory) FROM nodes WHERE repertoire_id=? AND fen_before IN (${chunk.joinToString(",") { "?" }}) GROUP BY fen_before,uci",arrayOf(rep,*chunk.toTypedArray()),cancellation)?.use {
                while(it.moveToNext()) { cancellation?.throwIfCanceled();states[it.getString(0) to it.getString(1)]=if(it.getInt(2)==1)Transition.ACTIVE else Transition.INFORMATIONAL }
            }
            for(pair in missing.filter { it.first in chunk }) {
                val added=localByBefore[pair.first].orEmpty().filter { it.uci==pair.second }
                val state=when {
                    added.any { effective(it).active } -> Transition.ACTIVE
                    pair in states -> states.getValue(pair)
                    added.isNotEmpty() -> Transition.RECONNECTABLE
                    else -> Transition.MISSING
                }
                transitions[pair]=state;sharedActivity?.putTransition(rep,pair.first,pair.second,state)
            }
        }
    }
    private fun comments(nodes: List<Node>, introduction: Boolean = false) = JSONArray(nodes.map { if(introduction)it.introduction else it.comment }.filter { it.isNotBlank() }.distinct())
    private fun positionComments(fen: String, original: JSONArray): JSONArray {
        val note = local.optJSONObject(commentKey(fen)) ?: return original
        return if(note.has("comment")) JSONArray().also { if(note.getString("comment").isNotEmpty()) it.put(note.getString("comment")) } else original
    }
    fun status(fen: String): JSONObject {
        cancellation?.throwIfCanceled()
        val nodes = allAt(fen);prepareFlags(nodes);val eligible = nodes.filter { effective(it).active }
        val optional = eligible.isNotEmpty() && eligible.all { effective(it).optional }
        val best = eligible.firstOrNull { !effective(it).optional } ?: eligible.firstOrNull() ?: nodes.firstOrNull()
        return JSONObject().put("fen",fen).put("known",nodes.isNotEmpty()).put("theory",eligible.isNotEmpty()).put("alternative",optional)
            .put("kind",if(eligible.isNotEmpty())"repertoire" else best?.kind ?: "unknown")
            .put("reason",best?.reason ?: "Outside this repertoire.").put("deviation",0)
            .put("end_of_line",eligible.isNotEmpty() && !hasContinuation(fen))
            .put("comments",positionComments(fen,comments(nodes))).put("starting_comments",comments(nodes,true))
            .also { sharedActivity?.put(rep,fen,eligible.isNotEmpty());if(local.optJSONObject(commentKey(fen))?.has("comment")==true) it.put("comment_edited",true).put("source_comments",comments(nodes)) }
    }
    fun moveJson(edge: Edge, side: String): JSONObject {
        val own = edge.before.split(' ').getOrNull(1) == if(side=="white")"w" else "b"
        val target = status(edge.fen)
        return JSONObject().put("uci",edge.uci).put("san",edge.san).put("fen",edge.fen).put("theory",edge.active)
            .put("recommendation",recommendation(edge,side)).put("deleted",deleted(edge))
            .put("alternative",edge.optional).put("own",own).put("kind",if(edge.active && edge.optional && own)"alternative" else edge.kind)
            .put("reason",edge.reason).put("edited",edge.edited).put("deviation",0)
            .put("comments",if(edge.reentry)target.getJSONArray("comments") else positionComments(edge.fen,comments(edge.nodes)))
            .put("starting_comments",if(edge.reentry)JSONArray() else comments(edge.nodes,true))
            .also { if(target.has("source_comments"))it.put("source_comments",target.getJSONArray("source_comments")) }
            .put("position",target).put("end_of_line",target.getBoolean("end_of_line"))
    }
    /** One ordinary save extends the most recent recorded position, whether its
     * imported route is theory or information. Both produce normal local edges.
     * Explicit exclusions/deletions must instead be restored deliberately. */
    fun additions(positions: List<String>, moves: List<String>, entries: JSONArray, anchor: Int = positions.indexOfLast(::active)): List<Addition>? {
        if (positions.size != moves.size+1 || entries.length()!=moves.size) return null
        val validationStart = if(anchor>=0)anchor else positions.indexOfFirst { fen ->
            factsAt(fen).let { prepareFlags(it);it.any(::sourceAllowed) }
        }
        if (validationStart < 0) return null
        prepareTransitions(positions,moves,validationStart)
        var start=validationStart
        for(ply in validationStart until moves.size) {
            val state=transition(positions[ply],moves[ply])
            if(state==Transition.BLOCKED)return null
            // Extend the recorded informational tail without changing earlier source
            // labels. If the final played move itself is informational, Add includes it.
            if(state==Transition.INFORMATIONAL)start=minOf(ply+1,moves.lastIndex)
        }
        val additions = linkedMapOf<String,Addition>()
        for (ply in start until moves.size) {
            cancellation?.throwIfCanceled()
            val state = transition(positions[ply],moves[ply])
            // Imported classification does not veto an intentional local extension.
            // A local exclusion/deletion still does, including its dependent source path.
            if (state==Transition.BLOCKED) return null
            if (state==Transition.MISSING || state==Transition.INFORMATIONAL) {
                val entry = entries.getJSONObject(ply)
                additions[edgeKey(positions[ply],moves[ply])] = Addition(positions[ply],positions[ply+1],moves[ply],entry.getString("san").take(16),ply==start && !active(positions[start]),ply)
            }
        }
        return additions.values.toList()
    }
}
