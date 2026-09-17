package com.instinctazero.android

import android.database.CursorWrapper
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Public, deterministic edited-line workload. Timings are host measurements, not phone guarantees. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepertoireEditedLinePerformanceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val root = RepertoireStore.START_POSITION
    private val line = listOf("e2e4", "c7c5", "g2g3", "d7d5", "e4d5", "d8d5", "g1f3", "b8c6", "f1g2")
    private lateinit var store: RepertoireStore
    private lateinit var source: File
    private lateinit var snapshot: JSONObject
    private val queries = mutableListOf<String>()
    private var rowsRead = 0
    private fun newStore() = RepertoireStore(context) { file ->
        SQLiteDatabase.openDatabase(file.path, { _, driver, table, query ->
            queries.add(query.toString())
            object : CursorWrapper(SQLiteCursor(driver, table, query)) {
                override fun moveToNext(): Boolean = super.moveToNext().also { if(it) rowsRead++ }
                override fun moveToFirst(): Boolean = super.moveToFirst().also { if(it) rowsRead++ }
            }
        }, SQLiteDatabase.OPEN_READONLY)
    }
    private fun request(moves: List<String>, intersections: Boolean = false): JSONObject {
        var fen = root; val entries = JSONArray()
        for(uci in moves) {
            val next = RepertoireLegalMoves.from(fen).single { it.uci == uci }
            entries.put(JSONObject().put("fen",next.fen).put("san",RepertoireLegalMoves.san(fen,uci)))
            fen = next.fen
        }
        return JSONObject().put("root",root).put("fen",fen).put("history",JSONArray(moves))
            .put("entries",entries).put("selected",JSONArray().put("book")).put("intersections",intersections)
    }
    private fun row(moves: List<String>) = store.lookup(request(moves)).getJSONArray("results").getJSONObject(0)
    private fun add(local: JSONObject, before: String, uci: String): String {
        val next = RepertoireLegalMoves.from(before).single { it.uci == uci }
        local.put(RepertoirePositionBook.edgeKey(before,uci),JSONObject().put("scope","position").put("added",true)
            .put("kind","repertoire").put("before",before).put("fen",next.fen).put("uci",uci)
            .put("san",RepertoireLegalMoves.san(before,uci)))
        return next.fen
    }
    @Before fun setup() {
        source = File(context.cacheDir,"edited-line.sqlite")
        SQLiteDatabase.openOrCreateDatabase(source,null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT,value TEXT)")
            db.execSQL("INSERT INTO meta VALUES('schema_version','1')")
            db.execSQL("CREATE TABLE repertoires(id TEXT,name TEXT,side TEXT,pgn TEXT)")
            db.execSQL("INSERT INTO repertoires VALUES('book','Synthetic Sicilian','white','synthetic.pgn')")
            db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,parent_id INTEGER,repertoire_id TEXT,path_id TEXT,uci TEXT,san TEXT,kind TEXT,theory INTEGER,line_alternative INTEGER,reason TEXT,comment TEXT,fen TEXT,starting_comment TEXT,fen_before TEXT,srs INTEGER)")
            db.execSQL("CREATE INDEX nodes_path ON nodes(repertoire_id,path_id)")
            db.execSQL("CREATE INDEX nodes_fen ON nodes(repertoire_id,fen)")
            db.execSQL("CREATE INDEX nodes_before ON nodes(repertoire_id,fen_before)")
            db.execSQL("CREATE INDEX nodes_parent ON nodes(parent_id)")
            db.execSQL("CREATE INDEX nodes_training ON nodes(repertoire_id,srs)")
            val history = listOf("e2e4","c7c5","g1f3","d7d6")
            val payload = request(history)
            db.beginTransaction()
            try {
                repeat(400) { occurrence ->
                    var before = ""; var parent: Int? = null
                    for(ply in 0..history.size) {
                        val id = occurrence * 5 + ply + 1
                        val fen = if(ply==0) root else payload.getJSONArray("entries").getJSONObject(ply-1).getString("fen")
                        db.execSQL("INSERT INTO nodes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,parent,"book",RepertoireStore.pathId(root,history.take(ply)),history.getOrNull(ply-1),history.getOrNull(ply-1),"repertoire",1,0,"Source", "Source note ${occurrence % 3}",fen,"",before,0))
                        before=fen;parent=id
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        store=newStore();source.inputStream().use { store.install(it,RepertoireStore.hash(source.readBytes())) }
        val local=JSONObject();var fen=request(line.take(2)).getString("fen")
        for(uci in line.drop(2))fen=add(local,fen,uci)
        // A deterministic library of unrelated legal additions, not private user data.
        val random=java.util.Random(19);fen=add(local,root,"a2a3")
        repeat(320) {
            val legal=RepertoireLegalMoves.from(fen)
            fen=if(legal.isEmpty())add(local,root,"a2a3") else add(local,fen,legal[random.nextInt(legal.size)].uci)
        }
        snapshot=JSONObject().put("v",1).put("edits",JSONObject().put("book",local)).put("settings",JSONObject())
        store.restoreBackup(snapshot);queries.clear();rowsRead=0
    }
    private fun canonical(value: Any?): String = when(value) {
        null,JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    @Test fun editedSicilianUsesBoundedWorkAcrossPreviouslyUnvisitedMoves() {
        val measurements=JSONArray()
        for(main in listOf(false,true)) {
            store.restoreBackup(snapshot)
            if(main)store.edit(request(line.take(2)).put("id","book").put("kind","main").also {
                it.getJSONArray("history").put("g2g3")
            })
            store=newStore()
            for(ply in 4..line.size) {
                val payload=request(line.take(ply),true)
                queries.clear();rowsRead=0
                val start=System.nanoTime()
                val marker=store.markers(payload)
                val result=store.lookup(payload)
                val elapsed=(System.nanoTime()-start)/1e6
                val count=queries.size;val read=rowsRead
                val actual=result.getJSONArray("results").getJSONObject(0)
                assertTrue(actual.getBoolean("theory"))
                assertEquals(ply==line.size,actual.getBoolean("end_of_line"))
                assertEquals(actual.getBoolean("end_of_line"),marker.getJSONArray("results").getJSONObject(0).getBoolean("end_of_line"))
                measurements.put(JSONObject().put("main",main).put("ply",ply).put("queries",count).put("rows",read).put("ms",elapsed)
                    .put("response",canonical(result)).put("marker",canonical(marker)))
                println("Edited Sicilian main=$main ply=$ply: $count queries, $read decoded rows, $elapsed ms")
                if(System.getenv("EDITED_LINE_BASELINE")!="1") {
                    assertTrue("No per-addition source reads: $count",count<=40)
                    assertTrue("Do not decode unrelated duplicate occurrences: $read",read<=2000)
                }
            }
        }
        System.getenv("EDITED_LINE_OUTPUT")?.let { File(it).writeText(measurements.toString(2)) }
        System.getenv("EDITED_LINE_REFERENCE")?.let { path ->
            val expected=JSONArray(File(path).readText())
            assertEquals(expected.length(),measurements.length())
            for(i in 0 until expected.length()) {
                assertEquals("Complete response $i",expected.getJSONObject(i).getString("response"),measurements.getJSONObject(i).getString("response"))
                assertEquals("Marker $i",expected.getJSONObject(i).getString("marker"),measurements.getJSONObject(i).getString("marker"))
            }
        }
    }
    @Test fun exclusionsUndoRestoreRestartAndSourceReplacementInvalidateResolvedEdits() {
        val before=canonical(row(line));assertTrue(row(line).getBoolean("theory"))
        val payload=request(line.take(3)).put("id","book").put("kind","delete").also { it.getJSONArray("history").put("d7d5") }
        store.edit(payload);assertFalse(row(line).getBoolean("theory"))
        val deleted=store.backupSnapshot();val token=store.undoInfo()!!.getString("token")
        store=newStore();assertFalse(row(line).getBoolean("theory"))
        store.undo(token);assertEquals(before,canonical(row(line)))
        store.restoreBackup(deleted);assertFalse(row(line).getBoolean("theory"))
        store.restoreBackup(snapshot);assertEquals(before,canonical(row(line)))
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE nodes SET theory=0,kind='analysis' WHERE parent_id IS NOT NULL")
        }
        source.inputStream().use { store.install(it,RepertoireStore.hash(source.readBytes())) }
        assertFalse("Source replacement must invalidate cached local anchors",row(line).getBoolean("theory"))
    }
    @Test fun cancellationDoesNotPublishPartiallyResolvedLocalState() {
        val cancel=CancellationSignal();cancel.cancel()
        assertThrows(OperationCanceledException::class.java) { store.markers(request(line),cancel) }
        assertTrue(row(line).getBoolean("theory"))
    }
}
