package com.instinctazero.android

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Runs only inside migration/import/edit transactions. Navigation never evaluates an
 * overlay graph: every origin is compiled into the same indexed positions and moves. */
internal class RepertoireProjectionBuilder(private val db:SQLiteDatabase,private val checkpoint:(String)->Unit={}) {
    companion object {
        // The source may retain PGN null moves and other non-playable analysis records.
        // Match the released reader's complete UCI format, not merely its length.
        private fun playableUci(column:String)="($column GLOB '[a-h][1-8][a-h][1-8]' OR $column GLOB '[a-h][1-8][a-h][1-8][qrbn]')"
        fun ancestrySql(before:String):String {
            val joins=" LEFT JOIN nodes p ON p.id=n.parent_id LEFT JOIN uz_entries e ON e.rep=n.repertoire_id AND e.entry_key=n.path_id "+
                "LEFT JOIN uz_entries g ON g.rep=n.repertoire_id AND g.valid_edge=1 AND g.\"before\"=$before AND g.uci=n.uci "
            val allowed="COALESCE(e.deleted,0)=0 AND COALESCE(e.kind,'')!='analysis' AND COALESCE(g.deleted,0)=0 AND COALESCE(g.kind,'')!='analysis'"
            val optional="COALESCE(e.kind,'')='alternative' OR COALESCE(g.kind,'')='alternative'"
            // Keep each current ancestor outside the indexed child lookup. SQLite may
            // otherwise reverse these loops and rescan the repertoire for every node.
            return """WITH RECURSIVE ancestry(id,allowed,optional,depth) AS (
                SELECT n.id,($allowed),($optional),0 FROM nodes n $joins
                WHERE n.repertoire_id=? AND NOT EXISTS(SELECT 1 FROM nodes parent WHERE parent.id=n.parent_id AND parent.repertoire_id=n.repertoire_id AND n.parent_id!=0)
                UNION ALL SELECT n.id,a.allowed AND ($allowed),a.optional OR ($optional),a.depth+1
                FROM ancestry a CROSS JOIN nodes n ON n.parent_id=a.id $joins WHERE n.repertoire_id=? AND a.depth<512 AND n.id!=a.id
                ) INSERT OR IGNORE INTO uz_masks SELECT id,allowed,optional FROM ancestry"""
        }
        fun schema(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE uz_library(rep TEXT PRIMARY KEY,name TEXT,side TEXT,pgn TEXT,local INTEGER,root TEXT)")
            db.execSQL("CREATE TABLE uz_occurrences(rep TEXT,oid TEXT,origin INTEGER,ordinal INTEGER,source_order INTEGER,path TEXT,before_fen TEXT,fen TEXT,uci TEXT,san TEXT,active INTEGER,optional INTEGER,allowed INTEGER,kind TEXT,reason TEXT,PRIMARY KEY(rep,oid))")
            db.execSQL("CREATE INDEX uz_occ_position ON uz_occurrences(rep,fen)")
            db.execSQL("CREATE INDEX uz_occ_edge ON uz_occurrences(rep,before_fen,uci)")
            db.execSQL("CREATE INDEX uz_occ_path ON uz_occurrences(rep,path)")
            db.execSQL("CREATE TABLE uz_positions(rep TEXT,fen TEXT,active INTEGER,optional INTEGER,kind TEXT,reason TEXT,source_seen INTEGER,source_allowed INTEGER,authored_active INTEGER,PRIMARY KEY(rep,fen))")
            db.execSQL("CREATE TABLE uz_edges(rep TEXT,before_fen TEXT,uci TEXT,fen TEXT,san TEXT,active INTEGER,optional INTEGER,kind TEXT,reason TEXT,edited INTEGER,deleted INTEGER,chosen INTEGER,transition TEXT,sort_order TEXT,PRIMARY KEY(rep,before_fen,uci))")
            db.execSQL("CREATE TABLE uz_notes(id INTEGER PRIMARY KEY,text TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE uz_position_notes(rep TEXT,fen TEXT,role INTEGER,ordinal INTEGER,note INTEGER,PRIMARY KEY(rep,fen,role,note))")
            db.execSQL("CREATE TABLE uz_edge_notes(rep TEXT,before_fen TEXT,uci TEXT,role INTEGER,ordinal TEXT,note INTEGER,PRIMARY KEY(rep,before_fen,uci,role,note))")
        }
    }
    fun rebuild(repertoires:Set<String>?=null) {
        db.execSQL("DELETE FROM uz_library")
        db.execSQL("INSERT INTO uz_library SELECT id,name,side,pgn,0,NULL FROM repertoires")
        val local=db.rawQuery("SELECT value FROM uz_config WHERE key='_local_repertoires'",null).use { if(it.moveToFirst())JSONObject(it.getString(0)) else JSONObject() }
        for(rep in local.keys()) {
            val item=local.getJSONObject(rep)
            db.execSQL("INSERT OR REPLACE INTO uz_library VALUES(?,?,?,?,1,?)",arrayOf(rep,item.getString("name"),item.getString("side"),"",item.getString("root")))
        }
        val all=db.rawQuery("SELECT rep FROM uz_library ORDER BY rep",null).use { rows -> buildList { while(rows.moveToNext())add(rows.getString(0)) } }
        val targets=repertoires ?: all.toSet()
        for(rep in targets) {
            for(table in listOf("uz_occurrences","uz_positions","uz_edges","uz_position_notes","uz_edge_notes"))db.execSQL("DELETE FROM $table WHERE rep=?",arrayOf(rep))
            if(rep !in all)continue
            source(rep)
            local.optJSONObject(rep)?.getString("root")?.let { root ->
                db.execSQL("INSERT INTO uz_occurrences VALUES(?, 'root',1,0,0,'','',?,'','',1,0,1,'repertoire','Created on this phone.')",arrayOf(rep,root))
            }
            authored(rep)
            project(rep)
        }
        // Removed source books retain personal records/history for a later restore, not stale coverage.
        for(table in listOf("uz_occurrences","uz_positions","uz_edges","uz_position_notes","uz_edge_notes"))db.execSQL("DELETE FROM $table WHERE rep NOT IN (SELECT rep FROM uz_library)")
        db.execSQL("DELETE FROM uz_notes WHERE id NOT IN (SELECT note FROM uz_position_notes UNION SELECT note FROM uz_edge_notes)")
    }
    private fun source(rep:String) {
        val columns=RepertoirePositionBook.readColumns(db)
        val before=if("fen_before" in columns)"COALESCE(n.fen_before,'')" else "COALESCE(p.fen,'')"
        val order=if("srs" in columns)"COALESCE(n.srs,0)" else "0"
        val masked=db.rawQuery("SELECT 1 FROM uz_entries WHERE rep=? AND (deleted=1 OR kind IN ('analysis','alternative')) LIMIT 1",arrayOf(rep)).use { it.moveToFirst() }
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS uz_masks(id INTEGER PRIMARY KEY,allowed INTEGER,optional INTEGER)")
        db.execSQL("DELETE FROM uz_masks")
        if(masked) {
            db.execSQL(ancestrySql(before),arrayOf(rep,rep))
        }
        val allowed=if(masked)"COALESCE(m.allowed,0)" else "1"
        val optional=if(masked)"n.line_alternative=1 OR COALESCE(m.optional,0)=1" else "n.line_alternative=1"
        db.execSQL("""INSERT INTO uz_occurrences SELECT n.repertoire_id,'s:'||n.id,0,n.id,$order,n.path_id,$before,n.fen,COALESCE(n.uci,''),COALESCE(n.san,''),
            n.theory=1 AND $allowed,($optional),$allowed,n.kind,COALESCE(n.reason,'')
            FROM nodes n LEFT JOIN nodes p ON p.id=n.parent_id LEFT JOIN uz_masks m ON m.id=n.id WHERE n.repertoire_id=?""",arrayOf(rep))
    }
    private data class Added(val key:String,val ordinal:Int,val scope:String,val before:String,val fen:String,val uci:String,val san:String,val kind:String,val deleted:Boolean,val anchored:Boolean,val parent:String)
    private data class Flags(val active:Boolean,val optional:Boolean=false)
    /** Plain user additions do not change any source mask or source note. Re-evaluate
     * authored reachability (including transpositions), but aggregate only changed facts.
     * This and Undo run in the same durable transaction as the authoritative entries. */
    fun rebuildAdditions(rep:String) {
        // Authored IDs already occupy a contiguous range of the (rep,oid) primary
        // key. Filtering origin alone scans every imported occurrence in the book.
        db.execSQL("CREATE TEMP TABLE uz_old_authored AS SELECT * FROM uz_occurrences WHERE rep=? AND oid>='a:' AND oid<'a;'",arrayOf(rep))
        checkpoint("add_capture")
        try {
            db.execSQL("DELETE FROM uz_occurrences WHERE rep=? AND oid>='a:' AND oid<'a;' AND path NOT IN (SELECT entry_key FROM uz_entries WHERE rep=? AND added=1)",arrayOf(rep,rep))
            authored(rep,incremental=true)
            checkpoint("add_authored")
            db.execSQL("CREATE TEMP TABLE uz_delta AS SELECT * FROM uz_old_authored EXCEPT SELECT * FROM uz_occurrences WHERE rep=? AND oid>='a:' AND oid<'a;'",arrayOf(rep))
            db.execSQL("INSERT INTO uz_delta SELECT * FROM uz_occurrences WHERE rep=? AND oid>='a:' AND oid<'a;' EXCEPT SELECT * FROM uz_old_authored",arrayOf(rep))
            db.execSQL("CREATE TEMP TABLE uz_dirty_positions(fen TEXT PRIMARY KEY)")
            db.execSQL("INSERT OR IGNORE INTO uz_dirty_positions SELECT fen FROM uz_delta")
            db.execSQL("CREATE TEMP TABLE uz_dirty_edges(before_fen TEXT,uci TEXT,PRIMARY KEY(before_fen,uci))")
            db.execSQL("INSERT OR IGNORE INTO uz_dirty_edges SELECT before_fen,uci FROM uz_delta")
            db.execSQL("DELETE FROM uz_positions WHERE rep=? AND fen IN (SELECT fen FROM uz_dirty_positions)",arrayOf(rep))
            db.execSQL("DELETE FROM uz_edges WHERE rep=? AND (before_fen,uci) IN (SELECT before_fen,uci FROM uz_dirty_edges)",arrayOf(rep))
            checkpoint("add_delta")
            project(rep,incremental=true)
            checkpoint("add_project")
        } finally {
            for(table in listOf("uz_old_authored","uz_delta","uz_dirty_positions","uz_dirty_edges"))db.execSQL("DROP TABLE IF EXISTS temp.$table")
        }
    }
    private fun authored(rep:String,incremental:Boolean=false) {
        val all=db.rawQuery("SELECT entry_key,ordinal,COALESCE(scope,''),COALESCE(\"before\",''),COALESCE(fen,''),COALESCE(uci,''),COALESCE(san,''),COALESCE(kind,''),COALESCE(deleted,0),COALESCE(anchored,0),COALESCE(parent,'') FROM uz_entries WHERE rep=? AND added=1 ORDER BY ordinal",arrayOf(rep)).use { rows ->
            buildList { while(rows.moveToNext())add(Added(rows.getString(0),rows.getInt(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),rows.getInt(8)==1,rows.getInt(9)==1,rows.getString(10))) }
        }
        if(all.isEmpty())return
        val previous=if(incremental)db.rawQuery("SELECT rep,oid,ordinal,path,before_fen,fen,uci,san,active,optional,kind FROM uz_occurrences WHERE rep=? AND oid>='a:' AND oid<'a;'",arrayOf(rep)).use {rows ->
            buildMap {while(rows.moveToNext())put(rows.getString(1),(0 until rows.columnCount).map {rows.getString(it)})}
        } else emptyMap()
        val byKey=all.associateBy { it.key };val states=mutableMapOf<String,Flags>()
        val reachable=mutableMapOf<String,Boolean>();val queue=ArrayDeque<String>()
        fun reach(fen:String,optional:Boolean) { val old=reachable[fen];if(old==null || old && !optional) {reachable[fen]=optional;queue.addLast(fen)} }
        fun sourceFlags(path:String):Flags=db.rawQuery("SELECT MAX(active),MIN(CASE WHEN active=1 THEN optional ELSE 1 END) FROM uz_occurrences WHERE rep=? AND path=? AND origin<2",arrayOf(rep,path)).use { it.moveToFirst();Flags(it.getInt(0)==1,it.getInt(1)==1) }
        fun override(before:String,uci:String):Pair<Boolean,Boolean> = db.rawQuery("SELECT COALESCE(deleted,0)=1 OR kind='analysis',kind='alternative' FROM uz_entries WHERE rep=? AND valid_edge=1 AND \"before\"=? AND uci=?",arrayOf(rep,before,uci)).use { if(it.moveToFirst())(it.getInt(0)==1) to (it.getInt(1)==1) else false to false }
        fun legacy(node:Added):Flags {
            var key=node.key;var optional=false;val seen=mutableSetOf<String>()
            repeat(513) {
                if(!seen.add(key))return Flags(false)
                val entry=byKey[key]
                if(entry!=null && entry.scope!="position") {
                    if(entry.deleted || entry.kind=="analysis")return Flags(false)
                    val before=byKey[entry.parent]?.fen ?: db.rawQuery("SELECT fen FROM uz_occurrences WHERE rep=? AND path=? ORDER BY ordinal LIMIT 1",arrayOf(rep,entry.parent)).use { if(it.moveToFirst())it.getString(0) else "" }
                    val global=override(before,entry.uci);if(global.first)return Flags(false)
                    optional=optional || entry.kind=="alternative" || global.second;key=entry.parent
                } else { val source=sourceFlags(key);return Flags(source.active,optional || source.optional) }
            }
            return Flags(false)
        }
        for(node in all.filter { it.scope!="position" }) {
            val state=legacy(node);states[node.key]=state;if(state.active)reach(node.fen,state.optional)
        }
        val edges=all.filter { it.scope=="position" }.groupBy { it.before }
        for(chunk in edges.keys.chunked(400)) {
            db.rawQuery("SELECT fen,MAX(active),MIN(CASE WHEN active=1 THEN optional ELSE 1 END),MAX(allowed) FROM uz_occurrences WHERE rep=? AND origin<2 AND fen IN (${chunk.joinToString(",") { "?" }}) GROUP BY fen",arrayOf(rep,*chunk.toTypedArray())).use { rows -> while(rows.moveToNext()) {
                val fen=rows.getString(0)
                if(rows.getInt(1)==1)reach(fen,rows.getInt(2)==1)
                if(rows.getInt(3)==1 && edges.getValue(fen).any { it.anchored && !it.deleted && it.kind!="analysis" })reach(fen,false)
            } }
        }
        while(queue.isNotEmpty()) {
            val before=queue.removeFirst()
            for(node in edges[before].orEmpty())if(!node.deleted && node.kind!="analysis") {
                val optional=reachable.getValue(before) || node.kind=="alternative"
                states[node.key]=Flags(true,optional);reach(node.fen,optional)
            }
        }
        for(node in all) {
            val before=if(node.scope=="position")node.before else byKey[node.parent]?.fen ?: db.rawQuery("SELECT fen FROM uz_occurrences WHERE rep=? AND path=? ORDER BY ordinal LIMIT 1",arrayOf(rep,node.parent)).use { if(it.moveToFirst())it.getString(0) else "" }
            val flags=states[node.key] ?: Flags(false)
            val values=arrayOf<Any>(rep,"a:"+node.key,node.ordinal,node.key,RepertoireStore.position(before),RepertoireStore.position(node.fen),node.uci,node.san,if(flags.active)1 else 0,if(flags.optional)1 else 0,node.kind)
            if(incremental) {
                if(previous["a:"+node.key]==values.map {it.toString()})continue
                db.execSQL("INSERT OR REPLACE INTO uz_occurrences VALUES(?,?,2,?,0,?,?,?,?,?,?,?,1,?,'Added on this phone.')",values)
            } else db.execSQL("INSERT INTO uz_occurrences VALUES(?,?,2,?,0,?,?,?,?,?,?,?,1,?,'Added on this phone.')",values)
        }
    }
    private fun project(rep:String,incremental:Boolean=false) {
        val positions=if(incremental)" AND o.fen IN (SELECT fen FROM uz_dirty_positions)" else ""
        val edges=if(incremental)" AND (o.before_fen,o.uci) IN (SELECT before_fen,uci FROM uz_dirty_edges)" else ""
        val positionOrder="b.active DESC,CASE WHEN b.active=1 THEN b.optional ELSE 0 END,b.origin,b.ordinal"
        fun positionBest(column:String)="(SELECT b.$column FROM uz_occurrences b WHERE b.rep=o.rep AND b.fen=o.fen ORDER BY $positionOrder LIMIT 1)"
        db.execSQL("""INSERT INTO uz_positions SELECT rep,fen,MAX(active),CASE WHEN MAX(active)=1 THEN MIN(CASE WHEN active=1 THEN optional ELSE 1 END) ELSE 0 END,
            CASE WHEN MAX(active)=1 THEN 'repertoire' ELSE ${positionBest("kind")} END,${positionBest("reason")},
            MAX(origin<2),MAX(origin<2 AND allowed=1),MAX(origin=2 AND active=1)
            FROM uz_occurrences o WHERE rep=? $positions GROUP BY rep,fen""",arrayOf(rep))
        val edgeOrder="b.active DESC,CASE WHEN b.active=1 THEN b.optional ELSE 0 END,b.origin,b.source_order,b.ordinal"
        fun edgeBest(column:String)="(SELECT b.$column FROM uz_occurrences b WHERE b.rep=o.rep AND b.before_fen=o.before_fen AND b.uci=o.uci ORDER BY $edgeOrder LIMIT 1)"
        db.execSQL("""INSERT INTO uz_edges SELECT o.rep,o.before_fen,o.uci,${edgeBest("fen")},${edgeBest("san")},MAX(o.active),
            CASE WHEN MAX(o.active)=1 THEN MIN(CASE WHEN o.active=1 THEN o.optional ELSE 1 END) ELSE 0 END,
            CASE WHEN MAX(o.active)=1 THEN 'repertoire' WHEN ${edgeBest("kind")} IN ('repertoire','alternative') THEN 'analysis' ELSE ${edgeBest("kind")} END,
            CASE WHEN e.kind='analysis' THEN 'Excluded on this phone. The original remains unchanged.' ELSE ${edgeBest("reason")} END,
            e.entry_key IS NOT NULL OR MAX(EXISTS(SELECT 1 FROM uz_entries p WHERE p.rep=o.rep AND p.entry_key=o.path)),COALESCE(e.deleted,0),COALESCE(e.kind,'')='main',
            CASE WHEN COALESCE(e.deleted,0)=1 OR e.kind='analysis' THEN 'BLOCKED' WHEN MAX(o.active)=1 THEN 'ACTIVE'
                WHEN MAX(o.origin<2 AND o.allowed=1)=1 THEN 'INFORMATIONAL'
                WHEN MIN(o.origin=2 AND o.kind!='analysis')=1 THEN 'RECONNECTABLE' ELSE 'BLOCKED' END,MIN(PRINTF('%d:%010d:%020d',o.origin,o.source_order,o.ordinal))
            FROM uz_occurrences o LEFT JOIN uz_entries e ON e.rep=o.rep AND e.valid_edge=1 AND e."before"=o.before_fen AND e.uci=o.uci
            WHERE o.rep=? $edges AND ${playableUci("o.uci")} GROUP BY o.rep,o.before_fen,o.uci""",arrayOf(rep))
        if(incremental)return
        // Read order is part of the UI contract. Deduplicate complete comments once at
        // revision build time, never truncate them during a move lookup.
        val columns=RepertoirePositionBook.readColumns(db)
        val before=if("fen_before" in columns)"COALESCE(n.fen_before,'')" else "COALESCE(p.fen,'')"
        val order=if("srs" in columns)"COALESCE(n.srs,0)" else "0"
        for((role,column) in listOf(0 to "comment",1 to "starting_comment"))if(column in columns) {
            db.execSQL("INSERT OR IGNORE INTO uz_notes(text) SELECT $column FROM nodes WHERE repertoire_id=? AND LENGTH(TRIM($column,CHAR(9)||CHAR(10)||CHAR(13)||' '))>0",arrayOf(rep))
            db.execSQL("INSERT INTO uz_position_notes SELECT n.repertoire_id,n.fen,?,MIN(n.id),t.id FROM nodes n JOIN uz_notes t ON t.text=n.$column WHERE n.repertoire_id=? GROUP BY n.fen,t.id",arrayOf<Any>(role,rep))
            db.execSQL("INSERT INTO uz_edge_notes SELECT n.repertoire_id,$before,n.uci,?,MIN(PRINTF('%010d:%020d',$order,n.id)),t.id FROM nodes n LEFT JOIN nodes p ON p.id=n.parent_id JOIN uz_notes t ON t.text=n.$column WHERE n.repertoire_id=? AND ${playableUci("n.uci")} GROUP BY $before,n.uci,t.id",arrayOf<Any>(role,rep))
        }
    }
}
