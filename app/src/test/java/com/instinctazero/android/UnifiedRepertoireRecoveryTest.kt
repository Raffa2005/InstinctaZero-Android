package com.instinctazero.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnifiedRepertoireRecoveryTest {
    private val root=RepertoireStore.START_POSITION
    private fun context(label:String):Context {
        val app=RuntimeEnvironment.getApplication()
        val folder=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),label).toFile()
        return object:ContextWrapper(app) {
            override fun getFilesDir()=folder
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(folder.name+name,mode)
        }
    }
    private fun request(moves:List<String>,ids:List<String> = listOf("book")):JSONObject {
        var fen=root;val entries=JSONArray()
        for(uci in moves) {val move=RepertoireLegalMoves.from(fen).single { it.uci==uci };entries.put(JSONObject().put("san",RepertoireLegalMoves.san(fen,uci)).put("fen",move.fen));fen=move.fen}
        return JSONObject().put("root",root).put("fen",fen).put("history",JSONArray(moves)).put("entries",entries).put("selected",JSONArray(ids)).put("intersections",true)
    }
    private fun fixture(folder:File,newer:Boolean=false):File {
        val file=File(folder,if(newer)"new.sqlite" else "old.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)");db.execSQL("INSERT INTO meta VALUES('schema_version','1')")
            db.execSQL("CREATE TABLE repertoires(id TEXT PRIMARY KEY,name TEXT,side TEXT,pgn TEXT)");db.execSQL("INSERT INTO repertoires VALUES('book','Synthetic opening','white','synthetic.pgn')")
            db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,parent_id INTEGER,repertoire_id TEXT,path_id TEXT,uci TEXT,san TEXT,fen TEXT,fen_before TEXT,theory INTEGER,line_alternative INTEGER,kind TEXT,reason TEXT,comment TEXT,starting_comment TEXT,srs INTEGER)")
            db.execSQL("CREATE INDEX nodes_fen ON nodes(repertoire_id,fen)");db.execSQL("CREATE INDEX nodes_before ON nodes(repertoire_id,fen_before)");db.execSQL("CREATE INDEX nodes_training ON nodes(repertoire_id,srs)")
            var id=if(newer)20000 else 1
            for((moves,information) in listOf(listOf("e2e4","c7c5","g1f3","d7d6") to false,listOf("e2e4","c7c5","g2g3") to true,listOf("e2e4","e7e5") to false)) {
                var parent:Int?=null;var before=""
                for(ply in 0..moves.size) {
                    val req=request(moves.take(ply));val fen=req.getString("fen");val info=information && ply==moves.size
                    val uci=moves.getOrNull(ply-1)
                    db.execSQL("INSERT INTO nodes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,parent,"book",RepertoireStore.pathId(root,moves.take(ply)),uci,if(uci==null)"" else req.getJSONArray("entries").getJSONObject(ply-1).getString("san"),fen,before,if(info)0 else 1,0,if(info)"analysis" else "repertoire","Source explanation",if(ply==1 && newer)"New source clarification" else "Original source comment",if(info)"Informational introduction" else "",0))
                    parent=id++;before=fen
                }
            }
        };return file
    }
    private fun install(store:RepertoireStore,source:File)=source.inputStream().use { store.install(it,RepertoireStore.hash(source.readBytes())) }
    private fun row(store:RepertoireStore,moves:List<String>,id:String="book")=store.lookup(request(moves,listOf(id))).getJSONArray("results").getJSONObject(0)
    private fun canon(value:Any?)=UnifiedRepertoireDatabase.canonical(value)
    private val extension=listOf("e2e4","c7c5","g2g3","d7d5","e4d5","d8d5")

    @Test fun migrationRefreshRestartUndoRestoreAndOldReaderRollbackPreservePersonalWork() {
        val ctx=context("migration-");val oldSource=fixture(ctx.cacheDir);val updated=fixture(ctx.cacheDir,true)
        oldSource.copyTo(File(ctx.filesDir,"mobile_repertoire.sqlite"))
        val old=LegacyRepertoireStore(ctx)
        old.edit(request(extension).put("id","book").put("kind","add"))
        old.edit(request(listOf("d2d4")).put("id","book").put("kind","add"))
        old.edit(request(listOf("d2d4")).put("fen",root).put("id","book").put("kind","main"))
        old.edit(request(listOf("e2e4","e7e5")).put("fen",request(listOf("e2e4")).getString("fen")).put("id","book").put("kind","delete"))
        val localId=old.saveRepertoire(JSONObject().put("name","Personal Black").put("side","black")).getString("created_id")
        old.edit(request(listOf("g1h3"),listOf(localId)).put("id",localId).put("kind","add"))
        old.saveSettings(JSONObject().put("analysis",JSONArray(listOf("book",localId))).put("_selected",JSONArray(listOf("book",localId))).put("_bookMarker",true).toString())
        old.edit(request(listOf("e2e4")).put("id","book").put("kind","comment").put("comment","Personal explanation"))
        val original=File(ctx.filesDir,"mobile_repertoire_edits.json").readBytes()
        val pgn=File(ctx.filesDir,"keep.pgn").apply { writeText("[Event \"Synthetic local PGN\"]\n1. e4 *") }
        val pgnBytes=pgn.readBytes();val game=File(ctx.filesDir,"saved-game.json").apply {writeText("{\"synthetic\":true}")};val gameBytes=game.readBytes()
        val positions=listOf(emptyList(),listOf("e2e4"),listOf("e2e4","c7c5","g2g3"),extension,listOf("d2d4"),listOf("e2e4","e7e5"))
        var store=RepertoireStore(ctx)
        for(moves in positions)assertEquals(canon(old.lookup(request(moves,listOf("book",localId)))),canon(store.lookup(request(moves,listOf("book",localId)))))
        assertArrayEquals(original,File(ctx.filesDir,"mobile_repertoire_edits.json").readBytes())
        assertArrayEquals(oldSource.readBytes(),File(ctx.filesDir,"mobile_repertoire.sqlite").readBytes())
        val token=store.undoInfo()!!.getString("token");val settings=store.settings()
        install(store,updated);store=RepertoireStore(ctx)
        val note=row(store,listOf("e2e4"));assertEquals("Personal explanation",note.getJSONArray("comments").getString(0));assertEquals("New source clarification",note.getJSONArray("source_comments").getString(0))
        assertTrue(row(store,extension).getBoolean("theory"));assertTrue(row(store,listOf("g1h3"),localId).getBoolean("theory"))
        assertFalse(row(store,listOf("e2e4")).getJSONArray("moves").toString().contains("e7e5"))
        val first=row(store,emptyList()).getJSONArray("moves");assertEquals("main",(0 until first.length()).map {first.getJSONObject(it)}.single {it.getString("uci")=="d2d4"}.getString("recommendation"))
        assertEquals(settings,store.settings());assertEquals(token,store.undoInfo()!!.getString("token"))
        val backup=store.backupSnapshot()
        store.undo(token);assertEquals("New source clarification",row(store,listOf("e2e4")).getJSONArray("comments").getString(0))
        store.restoreBackup(backup);assertEquals("Personal explanation",row(store,listOf("e2e4")).getJSONArray("comments").getString(0))
        val replacement=context("replacement-");val replacementStore=RepertoireStore(replacement);install(replacementStore,updated);replacementStore.restoreBackup(backup)
        val rollback=context("old-reader-");updated.copyTo(File(rollback.filesDir,"mobile_repertoire.sqlite"));val oldReader=LegacyRepertoireStore(rollback);oldReader.restoreBackup(backup)
        for(moves in positions) {
            val expected=canon(store.lookup(request(moves,listOf("book",localId))))
            assertEquals(expected,canon(replacementStore.lookup(request(moves,listOf("book",localId)))))
            assertEquals(expected,canon(oldReader.lookup(request(moves,listOf("book",localId)))))
        }
        assertArrayEquals(pgnBytes,pgn.readBytes());assertArrayEquals(gameBytes,game.readBytes())
        UnifiedRepertoireDatabase(ctx.filesDir).read().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM uz_revisions",null).use {it.moveToFirst();assertTrue(it.getInt(0)>=4)}
            db.rawQuery("SELECT COUNT(*) FROM uz_changes",null).use {it.moveToFirst();assertTrue(it.getInt(0)>0)}
        }
    }
    @Test fun interruptedMigrationAndSourceRefreshNeverPublishPartialState() {
        val app=RuntimeEnvironment.getApplication();val folder=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"fault-sources-").toFile()
        val old=fixture(folder);val newer=fixture(folder,true)
        for(phase in listOf("migration_projection","migration_publish")) {
            val ctx=context("interrupted-");old.copyTo(File(ctx.filesDir,"mobile_repertoire.sqlite"))
            File(ctx.filesDir,"mobile_repertoire_edits.json").writeText("{}")
            val failed=RepertoireStore(ctx,checkpoint={if(it==phase)throw IOException("Injected interruption")})
            assertThrows(IOException::class.java) {failed.catalog()}
            assertFalse(File(ctx.filesDir,"repertoire_library.sqlite").exists())
            assertArrayEquals(old.readBytes(),File(ctx.filesDir,"mobile_repertoire.sqlite").readBytes())
            File(ctx.filesDir,"repertoire_library.building").writeText("An interrupted private staging file")
            assertTrue(row(RepertoireStore(ctx),listOf("e2e4")).getBoolean("theory"))
        }
        for(phase in listOf("refresh_source","refresh_commit")) {
            val ctx=context("refresh-fault-");var store=RepertoireStore(ctx);install(store,old)
            store.edit(request(extension).put("id","book").put("kind","add"));val before=canon(store.backupSnapshot());val response=canon(row(store,extension));val fingerprint=store.catalog().getString("fingerprint")
            store=RepertoireStore(ctx,checkpoint={if(it==phase)throw IOException("Injected interruption")})
            assertThrows(IOException::class.java) {install(store,newer)}
            store=RepertoireStore(ctx)
            assertEquals(before,canon(store.backupSnapshot()));assertEquals(response,canon(row(store,extension)));assertEquals(fingerprint,store.catalog().getString("fingerprint"))
            assertEquals("Original source comment",row(store,listOf("e2e4")).getJSONArray("comments").getString(0))
            install(store,newer);assertEquals("New source clarification",row(store,listOf("e2e4")).getJSONArray("comments").getString(0));assertTrue(row(store,extension).getBoolean("theory"))
        }
    }
    @Test fun corruptLegacyInputIsNotSilentlyMigratedAsEmptyAndStaleWritersCannotLoseAnEdit() {
        val ctx=context("corrupt-");val file=File(ctx.filesDir,"mobile_repertoire_edits.json")
        file.writeText("{unfinished personal edits")
        assertThrows(org.json.JSONException::class.java) {RepertoireStore(ctx).catalog()}
        assertEquals("{unfinished personal edits",file.readText());assertFalse(File(ctx.filesDir,"repertoire_library.sqlite").exists())
        val valid=context("writers-");val first=RepertoireStore(valid)
        first.saveRepertoire(JSONObject().put("name","One").put("side","white"))
        val second=RepertoireStore(valid);second.backupSnapshot() // Capture a complete older revision.
        first.saveRepertoire(JSONObject().put("name","Two").put("side","black"))
        assertThrows(IllegalStateException::class.java) {second.saveRepertoire(JSONObject().put("name","Stale").put("side","white"))}
        val current=RepertoireStore(valid).catalog().getJSONArray("repertoires")
        assertEquals(setOf("One","Two"),(0 until current.length()).map {current.getJSONObject(it).getString("name")}.toSet())
    }
    @Test fun completingAnEditDuringActivityReplacementInvalidatesTheNewReadersCachedMiss() {
        val ctx=context("activity-replacement-");val writer=RepertoireStore(ctx)
        val id=writer.saveRepertoire(JSONObject().put("name","Local").put("side","white")).getString("created_id")
        val reader=RepertoireStore(ctx);val req=request(listOf("e2e4"),listOf(id))
        assertFalse(reader.lookup(req).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
        assertFalse(reader.markers(req).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
        writer.edit(JSONObject(req.toString()).put("id",id).put("kind","add"))
        UnifiedRepertoireDatabase(ctx.filesDir).read().use {db ->
            db.rawQuery("EXPLAIN QUERY PLAN SELECT * FROM uz_occurrences WHERE rep=? AND oid>='a:' AND oid<'a;'",arrayOf(id)).use {rows ->
                val plan=buildList {while(rows.moveToNext())add(rows.getString(3))}.joinToString()
                assertTrue(plan,plan.contains("oid>?") && plan.contains("oid<?"))
            }
        }
        assertTrue(reader.lookup(req).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
        assertTrue(reader.markers(req).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
        writer.undo(writer.undoInfo()!!.getString("token"))
        assertFalse(reader.lookup(req).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
    }

    @Test fun boundedJournalRetainsCurrentStateUndoAndLegacyRecoveryInputs() {
        val ctx=context("journal-");val store=RepertoireStore(ctx)
        val id=store.saveRepertoire(JSONObject().put("name","Personal").put("side","white")).getString("created_id")
        store.edit(request(listOf("e2e4"),listOf(id)).put("id",id).put("kind","add"))
        val token=store.undoInfo()!!.getString("token")
        repeat(105) {store.saveSettings(JSONObject().put("analysis",JSONArray().put(id)).put("_bookMarker",it%2==0).toString())}
        assertTrue(row(store,listOf("e2e4"),id).getBoolean("theory"))
        UnifiedRepertoireDatabase(ctx.filesDir).read().use {db ->
            db.rawQuery("SELECT COUNT(*) FROM uz_revisions",null).use {it.moveToFirst();assertEquals(100,it.getInt(0))}
            db.rawQuery("SELECT COUNT(*) FROM uz_changes WHERE revision NOT IN (SELECT id FROM uz_revisions)",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
            db.rawQuery("EXPLAIN QUERY PLAN SELECT id FROM nodes WHERE repertoire_id=? AND parent_id=?",arrayOf("book","1")).use {rows ->
                val plan=buildList {while(rows.moveToNext())add(rows.getString(3))}.joinToString()
                assertTrue(plan,plan.contains("uz_source_parent") && plan.contains("parent_id=?"))
            }
            db.execSQL("CREATE TEMP TABLE uz_masks(id INTEGER PRIMARY KEY,allowed INTEGER,optional INTEGER)")
            db.rawQuery("EXPLAIN QUERY PLAN "+RepertoireProjectionBuilder.ancestrySql("COALESCE(n.fen_before,'')"),arrayOf("book","book")).use {rows ->
                val plan=buildList {while(rows.moveToNext())add(rows.getString(3))}.joinToString()
                assertTrue(plan,plan.contains("uz_source_parent (repertoire_id=? AND parent_id=?)"))
            }
        }
        val reopened=RepertoireStore(ctx)
        assertEquals(token,reopened.undoInfo()!!.getString("token"));reopened.undo(token)
        assertFalse(row(reopened,listOf("e2e4"),id).getBoolean("theory"))
    }
}
