package com.instinctazero.android

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.instinctazero.android.RepertoireActivityCache.Transition
import com.instinctazero.android.RepertoirePositionBook.Edge
import com.instinctazero.android.RepertoirePositionBook.Addition
import org.json.JSONArray
import org.json.JSONObject

/** One read path for every move. Origin is only attribution/edit-history metadata;
 * there are no authored-move graph traversals or JSON overlays during navigation. */
internal class RepertoireIndexedBook(private val db:SQLiteDatabase,private val rep:String,
    private val cancellation:CancellationSignal?=null,private val shared:RepertoireActivityCache?=null) {
    private data class Position(val active:Boolean,val optional:Boolean,val kind:String,val reason:String,val sourceSeen:Boolean,val sourceAllowed:Boolean,val authoredActive:Boolean)
    private data class Adjustment(val kind:String,val deleted:Boolean)
    private val positions=object:LinkedHashMap<String,Position?>(128,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Position?>?)=size>8192
    }
    private val recorded=mutableMapOf<String,List<Edge>>()
    private val allEdges=mutableMapOf<String,List<Edge>>()
    private val adjustments=mutableMapOf<String,Map<String,Adjustment>>()
    private val transitions=mutableMapOf<Pair<String,String>,Transition>()
    private val statuses=mutableMapOf<String,JSONObject>()
    private val legalCandidates=mutableMapOf<String,List<RepertoireLegalMoves.Candidate>>()
    private fun check() { cancellation?.throwIfCanceled() }
    private fun facts(fens:List<String>) {
        for(chunk in fens.distinct().filterNot { positions.containsKey(it) }.chunked(400)) {
            check();chunk.forEach { positions[it]=null }
            db.rawQuery("SELECT fen,active,optional,kind,reason,source_seen,source_allowed,authored_active FROM uz_positions WHERE rep=? AND fen IN (${chunk.joinToString(",") { "?" }})",arrayOf(rep,*chunk.toTypedArray()),cancellation).use { rows ->
                while(rows.moveToNext()) { check();positions[rows.getString(0)]=Position(rows.getInt(1)==1,rows.getInt(2)==1,rows.getString(3),rows.getString(4),rows.getInt(5)==1,rows.getInt(6)==1,rows.getInt(7)==1) }
            }
            for(fen in chunk)shared?.put(rep,fen,positions[fen]?.active==true)
        }
    }
    private fun at(fen:String):Position? { facts(listOf(fen));return positions[fen] }
    fun active(fen:String):Boolean { check();return shared?.get(rep,fen) ?: (at(fen)?.active==true) }
    fun prepareHistory(history:List<String>) { facts(history) }
    private fun readAdjustments(fens:List<String>) {
        for(chunk in fens.distinct().filterNot { adjustments.containsKey(it) }.chunked(400)) {
            val found=chunk.associateWith { mutableMapOf<String,Adjustment>() }
            db.rawQuery("SELECT \"before\",uci,COALESCE(kind,''),COALESCE(deleted,0) FROM uz_entries WHERE rep=? AND valid_edge=1 AND \"before\" IN (${chunk.joinToString(",") { "?" }})",arrayOf(rep,*chunk.toTypedArray()),cancellation).use { rows ->
                while(rows.moveToNext()) {check();found.getValue(rows.getString(0))[rows.getString(1)]=Adjustment(rows.getString(2),rows.getInt(3)==1)}
            }
            adjustments.putAll(found)
        }
    }
    private fun adjustments(fen:String):Map<String,Adjustment> {readAdjustments(listOf(fen));return adjustments.getValue(fen)}
    private fun readEdges(fens:List<String>) {
        for(chunk in fens.distinct().filterNot { recorded.containsKey(it) }.chunked(400)) {
            val found=chunk.associateWith { mutableListOf<Edge>() }
            db.rawQuery("SELECT before_fen,uci,fen,san,active,optional,kind,reason,edited,transition FROM uz_edges WHERE rep=? AND before_fen IN (${chunk.joinToString(",") { "?" }}) ORDER BY sort_order",arrayOf(rep,*chunk.toTypedArray()),cancellation).use { rows ->
                while(rows.moveToNext()) {
                    check();val before=rows.getString(0);val uci=rows.getString(1)
                    found.getValue(before).add(Edge(uci,rows.getString(3),rows.getString(2),before,rows.getInt(4)==1,rows.getInt(5)==1,rows.getString(6),rows.getString(7),rows.getInt(8)==1))
                    transitions[before to uci]=Transition.valueOf(rows.getString(9))
                }
            }
            found.forEach { (fen,edges)->recorded[fen]=edges }
        }
    }
    private fun reentries(fen:String,known:List<Edge>):List<Edge> {
        val origin=at(fen)
        if(origin!=null && origin.sourceSeen && !origin.sourceAllowed && !origin.authoredActive)return emptyList()
        val legal=legalCandidates.getOrPut(fen) { RepertoireLegalMoves.from(fen).filter { move -> known.none { it.uci==move.uci } } }
        facts(legal.map { it.fen })
        return legal.mapNotNull { move ->
            check();val target=positions[move.fen]?.takeIf { it.active } ?: return@mapNotNull null
            val edit=adjustments(fen)[move.uci];val excluded=edit?.deleted==true || edit?.kind=="analysis"
            Edge(move.uci,RepertoireLegalMoves.san(fen,move.uci),move.fen,fen,!excluded,edit?.kind=="alternative" || target.optional,
                if(excluded)"analysis" else "repertoire",if(excluded)"Excluded on this phone. The original remains unchanged." else "Legal transposition into a covered repertoire position.",edit!=null,true)
        }
    }
    fun edges(fen:String):List<Edge> = allEdges.getOrPut(fen) {
        check();readEdges(listOf(fen));val listed=recorded.getValue(fen)
        (listed+reentries(fen,listed)).also { shared?.putChoices(rep,fen,it.filter { edge -> edge.active && !deleted(edge) }.map { edge -> edge.uci }) }
    }
    fun deleted(edge:Edge)=adjustments(edge.before)[edge.uci]?.deleted==true
    private fun hasContinuation(fen:String):Boolean {
        shared?.choices(rep,fen)?.let { return it.isNotEmpty() }
        readEdges(listOf(fen));val listed=recorded.getValue(fen)
        if(listed.any { it.active && !deleted(it) })return true
        return reentries(fen,listed).any { it.active && !deleted(it) }
    }
    fun recommendation(edge:Edge,side:String):String {
        if(!edge.active || deleted(edge))return "informational"
        if(edge.before.split(' ')[1] != if(side=="white")"w" else "b")return "reply"
        val candidates=edges(edge.before).filter { it.active && !deleted(it) }
        val chosen=candidates.firstOrNull { adjustments(edge.before)[it.uci]?.kind=="main" }
        if(chosen!=null)return if(chosen.uci==edge.uci)"main" else "alternative"
        if(candidates.size==1)return "main"
        if(edge.reentry || candidates.none { !it.optional })return "unassigned"
        return if(edge.optional)"alternative" else "main"
    }
    fun marker(fen:String):JSONObject {
        val active=active(fen)
        return JSONObject().put("fen",fen).put("theory",active).put("end_of_line",active && !hasContinuation(fen))
    }
    fun intersection(history:List<String>,moves:List<String>):Int {
        if(history.size!=moves.size+1)return -1
        // Batch indexed rows for the actual history. Origin has no effect on this path.
        readEdges(history);facts(history);readAdjustments(history)
        val targets=mutableSetOf<String>()
        for(fen in history.distinct())if(shared?.choices(rep,fen)==null) {
            check();val origin=positions[fen]
            if(origin!=null && origin.sourceSeen && !origin.sourceAllowed && !origin.authoredActive)continue
            val known=recorded.getValue(fen)
            val legal=legalCandidates.getOrPut(fen) { RepertoireLegalMoves.from(fen).filter { move -> known.none { it.uci==move.uci } } }
            legal.forEach { targets.add(it.fen) }
        }
        facts(targets.toList())
        for(ply in moves.indices.reversed()) {
            check();val fen=history[ply]
            val choices=shared?.choices(rep,fen) ?: edges(fen).filter { it.active && !deleted(it) }.map { it.uci }
            if(choices.any { it!=moves[ply] })return ply
        }
        return -1
    }
    private fun transition(fen:String,uci:String):Transition {
        val edit=adjustments(fen)[uci]
        if(edit?.deleted==true || edit?.kind=="analysis")return Transition.BLOCKED
        readEdges(listOf(fen));return transitions[fen to uci] ?: Transition.MISSING
    }
    private fun notes(fen:String,uci:String?=null,role:Int=0):JSONArray {
        val sql=if(uci==null)"SELECT n.text FROM uz_position_notes p JOIN uz_notes n ON n.id=p.note WHERE p.rep=? AND p.fen=? AND p.role=? ORDER BY p.ordinal" else "SELECT n.text FROM uz_edge_notes p JOIN uz_notes n ON n.id=p.note WHERE p.rep=? AND p.before_fen=? AND p.uci=? AND p.role=? ORDER BY p.ordinal"
        val args=if(uci==null)arrayOf(rep,fen,role.toString()) else arrayOf(rep,fen,uci,role.toString())
        return db.rawQuery(sql,args,cancellation).use { rows -> JSONArray().also { while(rows.moveToNext()){check();val note=rows.getString(0);if(note.isNotBlank())it.put(note)} } }
    }
    private val personalNotes=mutableMapOf<String,String?>()
    private fun personalNote(fen:String):String? {
        if(!personalNotes.containsKey(fen))personalNotes[fen]=db.rawQuery("SELECT comment FROM uz_entries WHERE rep=? AND entry_key=? AND comment IS NOT NULL",arrayOf(rep,RepertoirePositionBook.commentKey(fen)),cancellation).use { if(it.moveToFirst())it.getString(0) else null }
        return personalNotes[fen]
    }
    private fun noteOverride(fen:String,original:JSONArray):JSONArray = personalNote(fen)?.let { text -> JSONArray().also { if(text.isNotEmpty())it.put(text) } } ?: original
    fun status(fen:String):JSONObject = statuses.getOrPut(fen) {
        check();val position=at(fen);val active=position?.active==true;val comments=notes(fen)
        JSONObject().put("fen",fen).put("known",position!=null).put("theory",active).put("alternative",position?.optional==true)
            .put("kind",position?.kind ?: "unknown").put("reason",position?.reason ?: "Outside this repertoire.").put("deviation",0)
            .put("end_of_line",active && !hasContinuation(fen)).put("comments",noteOverride(fen,comments)).put("starting_comments",notes(fen,role=1))
            .also { if(personalNote(fen)!=null)it.put("comment_edited",true).put("source_comments",comments) }
    }.let { JSONObject(it.toString()) }
    fun moveJson(edge:Edge,side:String):JSONObject {
        val own=edge.before.split(' ').getOrNull(1)==if(side=="white")"w" else "b"
        val target=status(edge.fen)
        return JSONObject().put("uci",edge.uci).put("san",edge.san).put("fen",edge.fen).put("theory",edge.active)
            .put("recommendation",recommendation(edge,side)).put("deleted",deleted(edge)).put("alternative",edge.optional).put("own",own)
            .put("kind",if(edge.active && edge.optional && own)"alternative" else edge.kind).put("reason",edge.reason).put("edited",edge.edited).put("deviation",0)
            .put("comments",if(edge.reentry)target.getJSONArray("comments") else noteOverride(edge.fen,notes(edge.before,edge.uci)))
            .put("starting_comments",if(edge.reentry)JSONArray() else notes(edge.before,edge.uci,1))
            .also { if(target.has("source_comments"))it.put("source_comments",target.getJSONArray("source_comments")) }
            .put("position",target).put("end_of_line",target.getBoolean("end_of_line"))
    }
    fun occurrenceKeys(edge:Edge):List<Pair<String,Boolean>> = db.rawQuery("SELECT path,origin=2 FROM uz_occurrences WHERE rep=? AND before_fen=? AND uci=? ORDER BY origin,source_order,ordinal",arrayOf(rep,edge.before,edge.uci),cancellation).use { rows -> buildList { while(rows.moveToNext())add(rows.getString(0) to (rows.getInt(1)==1)) } }
    fun additions(positions:List<String>,moves:List<String>,entries:JSONArray,anchor:Int=positions.indexOfLast(::active)):List<Addition>? {
        if(positions.size!=moves.size+1 || entries.length()!=moves.size)return null
        facts(positions);readEdges(positions);readAdjustments(positions)
        val validationStart=if(anchor>=0)anchor else positions.indexOfFirst { at(it)?.sourceAllowed==true }
        if(validationStart<0)return null
        var start=validationStart
        for(ply in validationStart until moves.size) {
            val state=transition(positions[ply],moves[ply])
            if(state==Transition.BLOCKED)return null
            if(state==Transition.INFORMATIONAL)start=minOf(ply+1,moves.lastIndex)
        }
        val added=linkedMapOf<String,Addition>()
        for(ply in start until moves.size) {
            check();val state=transition(positions[ply],moves[ply])
            if(state==Transition.BLOCKED)return null
            if(state==Transition.MISSING || state==Transition.INFORMATIONAL)added[RepertoirePositionBook.edgeKey(positions[ply],moves[ply])]=
                Addition(positions[ply],positions[ply+1],moves[ply],entries.getJSONObject(ply).getString("san").take(16),ply==start && !active(positions[start]),ply)
        }
        return added.values.toList()
    }
}
