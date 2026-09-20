package com.instinctazero.android

import android.content.ContextWrapper
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Durable edit, restart and post-edit navigation, using public generated data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepertoireWriteBenchmarkTest {
    @Test fun addRestartUndoAndRefresh() {
        val path=System.getenv("WRITE_BENCH_FIXTURE");assumeTrue(path!=null)
        val fixture=File(path!!);val app=RuntimeEnvironment.getApplication()
        val directory=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"write-bench-").toFile()
        val context=object:ContextWrapper(app) {
            override fun getFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(directory.name+name,mode)
        }
        val source=File(fixture,"source.sqlite").takeIf {it.isFile} ?: File(fixture,"synthetic/annotations/repertoire.sqlite")
        source.copyTo(File(directory,"mobile_repertoire.sqlite"))
        File(directory,"mobile_repertoire_edits.json").writeText(JSONObject(File(fixture,"add1000.json").readText()).getJSONObject("edits").toString())
        val cases=JSONObject(File(fixture,"workloads.json").readText())
        val request=JSONObject(cases.getJSONObject("new_game").toString())
        val rep=request.getJSONArray("selected").getString(0)
        request.put("selected",JSONArray().put(rep))
        val stages=JSONArray();var stageAt=System.nanoTime()
        fun reader()=RepertoireStore(context,checkpoint={name -> val now=System.nanoTime();stages.put(JSONObject().put("stage",name).put("ms",(now-stageAt)/1e6));stageAt=now})
        var store=reader();store.catalog()
        if(System.getenv("WRITE_BENCH_SINGLE")=="1") {
            val last=store.lookup(request).getJSONArray("results").getJSONObject(0).getInt("add_from")
            val moves=request.getJSONArray("history");val entries=request.getJSONArray("entries")
            request.put("history",JSONArray((0 until last).map {moves.get(it)})).put("entries",JSONArray((0 until last).map {entries.get(it)}))
                .put("fen",entries.getJSONObject(last-1).getString("fen"))
        }
        val edit=JSONObject(request.toString()).put("kind","add").put("id",rep)
        val result=JSONArray()
        fun time(block:()->Unit):Double {val start=System.nanoTime();block();return (System.nanoTime()-start)/1e6}
        repeat(8) {
            stages.length().let { while(stages.length()>0)stages.remove(0) };stageAt=System.nanoTime()
            val row=JSONObject().put("add_ms",time {store.edit(edit)}).put("stages",JSONArray(stages.toString())).put("edit_label",store.undoInfo()!!.getString("label"))
            row.put("lookup_ms",time {store.markers(request);assertTrue(store.lookup(request).getJSONArray("results").getJSONObject(0).getBoolean("theory"))})
            val snapshot=store.backupSnapshot()
            store=reader()
            assertEquals(UnifiedRepertoireDatabase.canonical(snapshot),UnifiedRepertoireDatabase.canonical(store.backupSnapshot()))
            if(it==7) {
                android.database.sqlite.SQLiteDatabase.openDatabase(File(directory,"repertoire_library.sqlite").path,null,0).use {db ->
                    fun facts()=listOf("uz_occurrences","uz_positions","uz_edges","uz_position_notes","uz_edge_notes").map {table ->
                        db.rawQuery("SELECT * FROM $table WHERE rep=? ORDER BY 1,2,3",arrayOf(rep)).use {rows ->
                            buildList {while(rows.moveToNext())add((0 until rows.columnCount).map {col->if(rows.isNull(col))null else rows.getString(col)})}
                        }
                    }
                    val incremental=facts();db.beginTransaction()
                    fun digest(rows:Any)=RepertoireStore.hash(rows.toString().toByteArray())
                    try {RepertoireProjectionBuilder(db).rebuild(setOf(rep));assertEquals("Incremental facts must equal a complete rebuild",digest(incremental),digest(facts()));db.setTransactionSuccessful()}finally{db.endTransaction()}
                }
                source.inputStream().use {input->store.install(input,RepertoireStore.hash(source.readBytes()))}
                store=RepertoireStore(context)
                assertEquals(UnifiedRepertoireDatabase.canonical(snapshot.getJSONObject("edits")),UnifiedRepertoireDatabase.canonical(store.backupSnapshot().getJSONObject("edits")))
            }
            row.put("undo_ms",time {store.undo(store.undoInfo()!!.getString("token"))})
            assertFalse(store.lookup(request).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
            result.put(row)
        }
        System.getenv("WRITE_BENCH_OUTPUT")?.let {File(it).writeText(result.toString())}
        println("DURABLE_WRITE_BENCH $result")
    }
}
