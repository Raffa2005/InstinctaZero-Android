package com.instinctazero.android

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/** The sole writable repertoire authority after migration. Legacy files are recovery inputs,
 * never a second live store. JSON is only a compatibility transport for old PC backups. */
internal class UnifiedRepertoireDatabase(private val folder: File, private val checkpoint: (String) -> Unit = {}) {
    val file = File(folder,"repertoire_library.sqlite")
    private val stage = File(folder,"repertoire_library.building")
    private val fields = linkedMapOf("scope" to false,"kind" to false,"added" to true,"deleted" to true,
        "anchored" to true,"before" to false,"fen" to false,"uci" to false,"san" to false,"parent" to false,"comment" to false)
    val exists get()=file.isFile
    private fun write(path: File=file)=SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).apply {execSQL("PRAGMA synchronous=FULL")}
    fun read()=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY)
    private fun put(db: SQLiteDatabase,key:String,value:String)=db.execSQL("INSERT OR REPLACE INTO uz_state VALUES(?,?)",arrayOf(key,value))
    private fun value(db: SQLiteDatabase,key:String):String?=db.rawQuery("SELECT value FROM uz_state WHERE key=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0) else null }
    fun fingerprint():String=read().use { value(it,"source") ?: "" }
    fun checkVersion()=read().use { require(value(it,"schema")=="1") { "Unsupported repertoire library. The saved copy is unchanged." } }
    fun settings():String?=read().use { db -> db.rawQuery("SELECT value FROM uz_config WHERE key='_settings'",null).use {if(it.moveToFirst())it.getString(0) else null} }

    fun ensure(source:File,edits:JSONObject,undo:JSONObject?,fingerprint:String) {
        synchronized(migrationLock) { ensureLocked(source,edits,undo,fingerprint) }
    }
    private fun ensureLocked(source:File,edits:JSONObject,undo:JSONObject?,fingerprint:String) {
        if(exists) { checkVersion();return }
        // A previous interrupted build is derived, never the accepted library or legacy input.
        stage.delete();File(stage.path+"-journal").delete()
        try {
            if(source.isFile)source.copyTo(stage) else SQLiteDatabase.openOrCreateDatabase(stage,null).use { emptySource(it) }
            write(stage).use { db ->
                db.rawQuery("PRAGMA journal_mode=DELETE",null).use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous=FULL")
                transaction(db) {
                    schema(db);saveEntries(db,edits);put(db,"undo",undo?.toString() ?: "null")
                    put(db,"source",fingerprint)
                    RepertoireProjectionBuilder(db).rebuild()
                    checkpoint("migration_projection")
                    put(db,"schema","1")
                    revision(db,"migration",JSONObject(),edits,null,undo)
                }
                validate(db)
            }
            checkpoint("migration_publish")
            check(stage.renameTo(file)) { "Unable to publish repertoire library. Original files are unchanged." }
            generation.incrementAndGet()
        } finally { stage.delete() }
    }
    fun edits():JSONObject=read().use(::exportEntries)
    fun undo():JSONObject?=read().use { value(it,"undo")?.takeUnless { raw -> raw=="null" }?.let(::JSONObject) }
    fun revision():Long=read().use(::revisionNumber)
    private fun revisionNumber(db:SQLiteDatabase):Long=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM uz_revisions",null).use {it.moveToFirst();it.getLong(0)}
    data class EditSnapshot(val edits:JSONObject,val undo:JSONObject?,val revision:Long)
    fun editSnapshot():EditSnapshot=read().use {db ->
        val revision=revisionNumber(db);val edits=exportEntries(db);val undo=value(db,"undo")?.takeUnless {it=="null"}?.let(::JSONObject)
        check(revisionNumber(db)==revision) {"The repertoire changed while opening. Reopen it before editing."}
        EditSnapshot(edits,undo,revision)
    }

    fun commit(edits:JSONObject,undo:JSONObject?,reason:String="edit",expectedEntries:String?=null,
        expectedRevision:Long?=null,previousState:JSONObject?=null)=write().use { db ->
        var committedRevision=0L
        transaction(db) {
            checkpoint("edit_begin")
            check(expectedRevision==null || revisionNumber(db)==expectedRevision) { "The repertoire changed in another session. Reopen it before editing; your saved changes are intact." }
            val previous=if(expectedRevision!=null && previousState!=null)previousState else exportEntries(db)
            val oldUndo=value(db,"undo")?.takeUnless { it=="null" }?.let(::JSONObject)
            check(expectedEntries==null || canonical(previous)==expectedEntries) { "The repertoire changed in another session. Reopen it before editing; your saved changes are intact." }
            val changed=(previous.keys().asSequence().toSet()+edits.keys().asSequence().toSet()).filter { key -> !equivalent(previous.opt(key),edits.opt(key)) }.toSet()
            checkpoint("edit_diff")
            saveEntries(db,edits,previous)
            checkpoint("edit_entries")
            if(changed.any { it!="_settings" }) {
                fun graph(book:JSONObject?):JSONObject=JSONObject().also { result -> book?.keys()?.forEach { key -> val entry=book.getJSONObject(key);if(entry.optString("scope")!="comment")result.put(key,entry) } }
                val reps=if("_local_repertoires" in changed)null else changed.filterNot { it.startsWith("_") }.filter { !equivalent(graph(previous.optJSONObject(it)),graph(edits.optJSONObject(it))) }.toSet()
                val builder=RepertoireProjectionBuilder(db,checkpoint)
                if(reps==null)builder.rebuild() else for(rep in reps) {
                    val old=graph(previous.optJSONObject(rep));val next=graph(edits.optJSONObject(rep))
                    fun ordinary(value:JSONObject?)=value==null || (value.optBoolean("added") && value.optString("scope")=="position" && value.optString("kind")=="repertoire" && !value.optBoolean("deleted"))
                    val onlyAdditions=(old.keys().asSequence().toSet()+next.keys().asSequence().toSet()).all { key ->
                        equivalent(old.opt(key),next.opt(key)) || (ordinary(old.optJSONObject(key)) && ordinary(next.optJSONObject(key)))
                    }
                    if(onlyAdditions)builder.rebuildAdditions(rep) else builder.rebuild(setOf(rep))
                }
            }
            checkpoint("edit_projection")
            put(db,"undo",undo?.toString() ?: "null")
            revision(db,reason,previous,edits,oldUndo,undo)
            committedRevision=revisionNumber(db)
            checkpoint("edit_commit")
        }
        generation.incrementAndGet()
        committedRevision
    }
    /** Copy source provenance into this database in one rollback-safe transaction. The
     * normalized personal records and revision history never depend on incoming node IDs. */
    fun refresh(source:File,fingerprint:String)=write().use { db ->
        db.execSQL("ATTACH DATABASE ? AS incoming",arrayOf(source.path))
        try {
            transaction(db) {
                val names=listOf("meta","repertoires","games","rules","nodes","preferences")
                val definitions=mutableListOf<Pair<String,String>>()
                db.rawQuery("SELECT name,sql FROM incoming.sqlite_master WHERE type='table'",null).use { rows ->
                    while(rows.moveToNext())if(rows.getString(0) in names)definitions.add(rows.getString(0) to rows.getString(1))
                }
                require(definitions.map { it.first }.containsAll(listOf("meta","repertoires","nodes")))
                for(name in names)db.execSQL("DROP TABLE IF EXISTS \"$name\"")
                for((name,sql) in definitions) { db.execSQL(sql);db.execSQL("INSERT INTO \"$name\" SELECT * FROM incoming.\"$name\"") }
                sourceIndexes(db)
                checkpoint("refresh_source")
                RepertoireProjectionBuilder(db).rebuild()
                put(db,"source",fingerprint)
                val current=exportEntries(db);val undo=value(db,"undo")?.takeUnless { it=="null" }?.let(::JSONObject)
                revision(db,"source refresh",current,current,undo,undo)
                checkpoint("refresh_commit")
            }
            generation.incrementAndGet()
        } finally { db.execSQL("DETACH DATABASE incoming") }
    }
    /** Explicit downgrade artifact, never automatic mirroring or a second authority. */
    fun exportLegacyEdits():JSONObject=edits().also { result -> undo()?.let { result.put("_undo",it) } }

    private fun schema(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE uz_state(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE uz_entries(rep TEXT NOT NULL,entry_key TEXT NOT NULL,ordinal INTEGER NOT NULL,"+
            fields.map { (field,boolean)->"\"$field\" ${if(boolean)"INTEGER" else "TEXT"}" }.joinToString(",")+",valid_edge INTEGER NOT NULL,PRIMARY KEY(rep,entry_key))")
        db.execSQL("CREATE INDEX uz_entries_edge ON uz_entries(rep,\"before\",uci) WHERE valid_edge=1")
        db.execSQL("CREATE TABLE uz_extra(rep TEXT,entry_key TEXT,field TEXT,value TEXT NOT NULL,PRIMARY KEY(rep,entry_key,field))")
        db.execSQL("CREATE TABLE uz_config(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE uz_edit_books(rep TEXT PRIMARY KEY,ordinal INTEGER)")
        db.execSQL("CREATE TABLE uz_revisions(id INTEGER PRIMARY KEY,created INTEGER,reason TEXT,source TEXT,undo_before TEXT,undo_after TEXT)")
        db.execSQL("CREATE TABLE uz_changes(revision INTEGER,rep TEXT,entry_key TEXT,before_json TEXT,after_json TEXT,PRIMARY KEY(revision,rep,entry_key))")
        RepertoireProjectionBuilder.schema(db)
        sourceIndexes(db)
    }
    private fun saveEntries(db:SQLiteDatabase,edits:JSONObject,previous:JSONObject=JSONObject()) {
        for(rep in previous.keys())if(!edits.has(rep)) {
            if(rep.startsWith("_"))db.execSQL("DELETE FROM uz_config WHERE key=?",arrayOf(rep))
            else {
                for(table in listOf("uz_entries","uz_extra","uz_edit_books"))db.execSQL("DELETE FROM $table WHERE rep=?",arrayOf(rep))
            }
        }
        for((repOrdinal,rep) in edits.keys().asSequence().withIndex()) {
            if(rep.startsWith("_")) {
                if(!equivalent(previous.opt(rep),edits.get(rep)))db.execSQL("INSERT OR REPLACE INTO uz_config VALUES(?,?)",arrayOf(rep,edits.get(rep).toString()))
                continue
            }
            db.execSQL("INSERT OR REPLACE INTO uz_edit_books VALUES(?,?)",arrayOf(rep,repOrdinal))
            val book=edits.getJSONObject(rep)
            val old=previous.optJSONObject(rep) ?: JSONObject()
            for(key in old.keys())if(!book.has(key)) {
                db.execSQL("DELETE FROM uz_entries WHERE rep=? AND entry_key=?",arrayOf(rep,key))
                db.execSQL("DELETE FROM uz_extra WHERE rep=? AND entry_key=?",arrayOf(rep,key))
            }
            val oldOrdinals=old.keys().asSequence().withIndex().associate {it.value to it.index}
            for((ordinal,key) in book.keys().asSequence().withIndex()) {
                val entry=book.getJSONObject(key)
                if(equivalent(old.opt(key),entry)) {
                    if(oldOrdinals[key]!=ordinal)db.execSQL("UPDATE uz_entries SET ordinal=? WHERE rep=? AND entry_key=?",arrayOf(ordinal,rep,key))
                    continue
                }
                db.execSQL("DELETE FROM uz_entries WHERE rep=? AND entry_key=?",arrayOf(rep,key))
                db.execSQL("DELETE FROM uz_extra WHERE rep=? AND entry_key=?",arrayOf(rep,key))
                val row=ContentValues()
                row.put("rep",rep);row.put("entry_key",key);row.put("ordinal",ordinal)
                for((field,boolean) in fields)if(entry.has(field) && !entry.isNull(field)) {
                    if(boolean)row.put(field,if(entry.getBoolean(field))1 else 0) else row.put(field,entry.getString(field))
                }
                row.put("valid_edge",if(entry.optString("scope")=="position" && key==RepertoirePositionBook.edgeKey(entry.optString("before"),entry.optString("uci")))1 else 0)
                db.insertOrThrow("uz_entries",null,row)
                for(field in entry.keys())if(field !in fields || entry.isNull(field))db.execSQL("INSERT INTO uz_extra VALUES(?,?,?,?)",arrayOf(rep,key,field,canonical(entry.get(field))))
            }
        }
    }
    private fun exportEntries(db:SQLiteDatabase):JSONObject {
        val result=JSONObject()
        db.rawQuery("SELECT key,value FROM uz_config ORDER BY rowid",null).use { rows -> while(rows.moveToNext())result.put(rows.getString(0),JSONObject(rows.getString(1))) }
        db.rawQuery("SELECT rep FROM uz_edit_books ORDER BY ordinal",null).use { rows -> while(rows.moveToNext())result.put(rows.getString(0),JSONObject()) }
        db.rawQuery("SELECT rep,entry_key,"+fields.keys.joinToString(",") { "\"$it\"" }+" FROM uz_entries ORDER BY rep,ordinal",null).use { rows ->
            while(rows.moveToNext()) {
                val book=result.optJSONObject(rows.getString(0)) ?: JSONObject().also { result.put(rows.getString(0),it) }
                val entry=JSONObject();book.put(rows.getString(1),entry)
                for((i,field) in fields.keys.withIndex())if(!rows.isNull(i+2))entry.put(field,if(fields.getValue(field))rows.getInt(i+2)==1 else rows.getString(i+2))
            }
        }
        db.rawQuery("SELECT rep,entry_key,field,value FROM uz_extra",null).use { rows -> while(rows.moveToNext()) {
            val value=org.json.JSONTokener(rows.getString(3)).nextValue()
            result.getJSONObject(rows.getString(0)).getJSONObject(rows.getString(1)).put(rows.getString(2),value)
        } }
        return result
    }
    private fun revision(db:SQLiteDatabase,reason:String,before:JSONObject,after:JSONObject,oldUndo:JSONObject?,undo:JSONObject?) {
        val values=ContentValues().apply { put("created",System.currentTimeMillis());put("reason",reason);put("source",value(db,"source") ?: "");put("undo_before",oldUndo?.toString());put("undo_after",undo?.toString()) }
        val id=db.insertOrThrow("uz_revisions",null,values)
        for(rep in (before.keys().asSequence().toSet()+after.keys().asSequence().toSet())) {
            if(rep.startsWith("_")) {
                if(!equivalent(before.opt(rep),after.opt(rep)))db.execSQL("INSERT INTO uz_changes VALUES(?,?,?,?,?)",arrayOf(id,rep,"",canonical(before.opt(rep)),canonical(after.opt(rep))))
            } else {
                val old=before.optJSONObject(rep) ?: JSONObject();val next=after.optJSONObject(rep) ?: JSONObject()
                for(key in old.keys().asSequence().toSet()+next.keys().asSequence().toSet())if(!equivalent(old.opt(key),next.opt(key)))db.execSQL("INSERT INTO uz_changes VALUES(?,?,?,?,?)",arrayOf(id,rep,key,canonical(old.opt(key)),canonical(next.opt(key))))
            }
        }
        // The current graph and single-step Undo are independent of retained audit history.
        // Match the PC's bounded backup history without growing the phone journal forever.
        db.execSQL("DELETE FROM uz_changes WHERE revision IN (SELECT id FROM uz_revisions ORDER BY id DESC LIMIT -1 OFFSET 100)")
        db.execSQL("DELETE FROM uz_revisions WHERE id NOT IN (SELECT id FROM uz_revisions ORDER BY id DESC LIMIT 100)")
    }
    private fun transaction(db:SQLiteDatabase,block:()->Unit) {
        db.beginTransaction()
        try { block();db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun validate(db:SQLiteDatabase) {
        db.rawQuery("PRAGMA quick_check",null).use { require(it.moveToFirst() && it.getString(0)=="ok") }
        require(value(db,"schema")=="1")
        db.rawQuery("SELECT COUNT(*) FROM uz_occurrences",null).use { require(it.moveToFirst()) }
    }
    private fun sourceIndexes(db:SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS nodes_path ON nodes(repertoire_id,path_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS nodes_fen ON nodes(repertoire_id,fen)")
        db.execSQL("CREATE INDEX IF NOT EXISTS nodes_parent ON nodes(parent_id)")
        // An ancestry rebuild constrains both columns. Without this index SQLite can
        // choose the repertoire-only prefix and rescan a whole book for each parent.
        db.execSQL("CREATE INDEX IF NOT EXISTS uz_source_parent ON nodes(repertoire_id,parent_id)")
    }
    private fun emptySource(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)")
        db.execSQL("INSERT INTO meta VALUES('schema_version','1')")
        db.execSQL("CREATE TABLE repertoires(id TEXT PRIMARY KEY,name TEXT,side TEXT,pgn TEXT)")
        db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,parent_id INTEGER,repertoire_id TEXT,path_id TEXT,uci TEXT,san TEXT,fen TEXT,fen_before TEXT,theory INTEGER,line_alternative INTEGER,kind TEXT,reason TEXT,comment TEXT,starting_comment TEXT,srs INTEGER)")
    }
    companion object {
        private val migrationLock=Any()
        val generation=java.util.concurrent.atomic.AtomicLong()
        fun equivalent(a:Any?,b:Any?):Boolean=when {
            a===b -> true
            a is JSONObject && b is JSONObject -> a.length()==b.length() && a.keys().asSequence().all { b.has(it) && equivalent(a.get(it),b.get(it)) }
            a is org.json.JSONArray && b is org.json.JSONArray -> a.length()==b.length() && (0 until a.length()).all { equivalent(a.get(it),b.get(it)) }
            else -> a==b
        }
        fun canonical(value:Any?):String=when(value) {
            null,JSONObject.NULL->"null"
            is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
            is org.json.JSONArray->(0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
            is String->JSONObject.quote(value)
            else->value.toString()
        }
    }
}
