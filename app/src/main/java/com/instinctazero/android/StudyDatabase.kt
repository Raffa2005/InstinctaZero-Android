package com.instinctazero.android

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Board edits and cursor commits are small synchronous transactions, not deferred full
 * tree snapshots. Legacy preferences stay untouched as a recovery copy. No PGN is edited. */
internal class StudyDatabase(private val folder:File,private val legacy:SharedPreferences,
    private val checkpoint:(String)->Unit={}) {
    private val file=File(folder,"analysis_boards.sqlite")
    private fun open():SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(file,null).apply {
        execSQL("PRAGMA synchronous=FULL")
        execSQL("CREATE TABLE IF NOT EXISTS boards(slot TEXT PRIMARY KEY,metadata TEXT NOT NULL,revision INTEGER NOT NULL)")
        execSQL("CREATE TABLE IF NOT EXISTS moves(slot TEXT,id INTEGER,parent INTEGER,ordinal INTEGER,uci TEXT,PRIMARY KEY(slot,id))")
        execSQL("CREATE INDEX IF NOT EXISTS moves_parent ON moves(slot,parent)")
        execSQL("CREATE TABLE IF NOT EXISTS active(slot TEXT)")
    }
    private fun slot(state:JSONObject)=if(state.optBoolean("editedPosition"))"position" else "study"
    private fun transaction(db:SQLiteDatabase,block:()->Unit) {
        db.beginTransaction();try {block();checkpoint("commit");db.setTransactionSuccessful()} finally {db.endTransaction()}
    }
    private fun backupLegacy() {
        val target=File(folder,"analysis-legacy-before-migration.json")
        if(target.isFile)return
        val raw=JSONObject().put("study",legacy.getString("state_v1","{}"))
            .put("position",legacy.getString("position_state_v1","{}"))
            .put("position_active",legacy.getBoolean("position_active",false)).toString()
        val atomic=AtomicFile(target);val output=atomic.startWrite()
        try {output.write(raw.toByteArray());atomic.finishWrite(output)} catch(e:Exception) {atomic.failWrite(output);throw e}
    }
    /** Import all legacy nodes, not just the first 512. An invalid input fails closed. */
    private fun prepare(db:SQLiteDatabase)=synchronized(migrationLock) {
        if(db.rawQuery("SELECT 1 FROM active",null).use {it.moveToFirst()})return@synchronized
        backupLegacy()
        transaction(db) {
            for((slot,key) in listOf("study" to "state_v1","position" to "position_state_v1")) {
                val raw=legacy.getString(key,"{}") ?: "{}";val state=JSONObject(raw)
                if(state.length()==0)continue
                require(state.getInt("v")==1)
                var id=0
                data class Pending(val children:JSONArray,val parent:Int)
                val stack=ArrayDeque<Pending>();stack.addLast(Pending(state.getJSONArray("tree"),0))
                while(stack.isNotEmpty()) {
                    val entry=stack.removeLast();val descendants=mutableListOf<Pending>()
                    for(i in 0 until entry.children.length()) {
                        val node=entry.children.getJSONObject(i);val next=++id
                        insert(db,slot,next,entry.parent,i,node.getString("u"))
                        descendants.add(Pending(node.getJSONArray("c"),next))
                    }
                    descendants.asReversed().forEach(stack::addLast)
                }
                state.remove("tree");state.put("v",2)
                db.execSQL("INSERT INTO boards VALUES(?,?,0)",arrayOf(slot,state.toString()))
            }
            db.execSQL("INSERT INTO active VALUES(?)",arrayOf(if(legacy.getBoolean("position_active",false))"position" else "study"))
        }
    }
    private fun insert(db:SQLiteDatabase,slot:String,id:Int,parent:Int,ordinal:Int,uci:String) {
        require(id>0 && parent>=0 && id!=parent && uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")))
        db.execSQL("INSERT INTO moves VALUES(?,?,?,?,?)",arrayOf(slot,id,parent,ordinal,uci))
    }
    @Synchronized fun current(source:Boolean=false):String=open().use {db ->
        prepare(db)
        db.beginTransactionNonExclusive()
        try {
        val slot=if(source)"study" else db.rawQuery("SELECT slot FROM active",null).use {it.moveToFirst();it.getString(0)}
        val result=db.rawQuery("SELECT metadata,revision FROM boards WHERE slot=?",arrayOf(slot)).use {
            if(!it.moveToFirst())return@use null
            JSONObject(it.getString(0)).put("revision",it.getLong(1))
        } ?: return@use "{}"
        val nodes=JSONArray()
        db.rawQuery("SELECT id,parent,ordinal,uci FROM moves WHERE slot=? ORDER BY parent,ordinal",arrayOf(slot)).use {rows ->
            while(rows.moveToNext())nodes.put(JSONObject().put("id",rows.getInt(0)).put("p",rows.getInt(1)).put("o",rows.getInt(2)).put("u",rows.getString(3)))
        }
        result.put("nodes",nodes).toString()
        } finally {db.endTransaction()}
    }
    @Synchronized fun revision(edited:Boolean):Long=open().use {db ->
        prepare(db)
        db.rawQuery("SELECT revision FROM boards WHERE slot=?",arrayOf(if(edited)"position" else "study")).use {if(it.moveToFirst())it.getLong(0) else 0L}
    }
    /** Compatibility for callers with a complete v1 snapshot; the old preferences are
     * never made a competing writable authority after migration. */
    @Synchronized fun saveLegacy(raw:String?):Boolean=runCatching {
        val state=JSONObject(requireNotNull(raw));require(state.getInt("v")==1)
        val branches=JSONArray();var id=0
        val pending=ArrayDeque<Pair<Int,JSONArray>>();pending.addLast(0 to state.getJSONArray("tree"))
        while(pending.isNotEmpty()) {
            val (parent,children)=pending.removeFirst();val wire=JSONArray()
            for(i in 0 until children.length()) {
                val child=children.getJSONObject(i);val next=++id
                wire.put(JSONObject().put("id",next).put("u",child.getString("u")))
                pending.addLast(next to child.getJSONArray("c"))
            }
            branches.put(JSONObject().put("parent",parent).put("children",wire))
        }
        state.remove("tree");state.put("v",2)
        JSONObject(save(JSONObject().put("revision",revision(state.optBoolean("editedPosition"))).put("meta",state)
            .put("replace",true).put("branches",branches).toString())).getBoolean("saved")
    }.getOrDefault(false)
    /** Returns revision only after SQLite confirms the commit. Any error leaves both
     * the previous tree and its cursor intact and the JS pending edit available to retry. */
    @Synchronized fun save(raw:String):String=try {
        val request=JSONObject(raw);val meta=request.getJSONObject("meta")
        require(meta.getInt("v")==2 && (!meta.optBoolean("editedPosition") || meta.isNull("gameId")))
        val slot=slot(meta)
        var revision=0L
        open().use {db ->
            prepare(db)
            transaction(db) {
                val old=db.rawQuery("SELECT revision FROM boards WHERE slot=?",arrayOf(slot)).use {if(it.moveToFirst())it.getLong(0) else 0L}
                require(request.getLong("revision")==old) {"Saved board changed in another session."}
                if(request.optBoolean("replace"))db.execSQL("DELETE FROM moves WHERE slot=?",arrayOf(slot))
                val branches=request.getJSONArray("branches")
                for(i in 0 until branches.length()) {
                    val branch=branches.getJSONObject(i);val parent=branch.getInt("parent");val children=branch.getJSONArray("children")
                    require(parent==0 || db.rawQuery("SELECT 1 FROM moves WHERE slot=? AND id=?",arrayOf(slot,parent.toString())).use {it.moveToFirst()})
                    val wanted=(0 until children.length()).map {children.getJSONObject(it).getInt("id")}.toSet()
                    require(wanted.size==children.length())
                    val previous=db.rawQuery("SELECT id FROM moves WHERE slot=? AND parent=?",arrayOf(slot,parent.toString())).use {rows -> buildList {while(rows.moveToNext())add(rows.getInt(0))}}
                    for(id in previous)if(id !in wanted) {
                        db.execSQL("WITH RECURSIVE removed(id) AS (SELECT ? UNION ALL SELECT m.id FROM removed r JOIN moves m ON m.parent=r.id WHERE m.slot=?) DELETE FROM moves WHERE slot=? AND id IN (SELECT id FROM removed)",arrayOf(id,slot,slot))
                    }
                    for(index in 0 until children.length()) {
                        val child=children.getJSONObject(index);val id=child.getInt("id");val uci=child.getString("u")
                        if(id in previous) {
                            require(db.rawQuery("SELECT uci FROM moves WHERE slot=? AND id=?",arrayOf(slot,id.toString())).use {it.moveToFirst() && it.getString(0)==uci})
                            db.execSQL("UPDATE moves SET ordinal=? WHERE slot=? AND id=?",arrayOf(index,slot,id))
                        } else insert(db,slot,id,parent,index,uci)
                    }
                }
                revision=old+1
                db.execSQL("INSERT OR REPLACE INTO boards VALUES(?,?,?)",arrayOf(slot,meta.toString(),revision))
                db.execSQL("UPDATE active SET slot=?",arrayOf(slot))
            }
        }
        JSONObject().put("saved",true).put("revision",revision).toString()
    } catch(_:Exception) {JSONObject().put("saved",false).put("message","Board not saved. Keep this board open and retry; the last saved copy is unchanged.").toString()}
    companion object {private val migrationLock=Any()}
}
