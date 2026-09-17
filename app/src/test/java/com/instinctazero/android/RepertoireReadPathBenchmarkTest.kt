package com.instinctazero.android

import android.content.Context
import android.content.ContextWrapper
import android.database.CursorWrapper
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Intentionally portable unchanged to v0.8.9 and PR2 for same-host comparisons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepertoireReadPathBenchmarkTest {
    private fun canonical(value:Any?):String=when(value) {
        null,JSONObject.NULL->"null"
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}") {JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]") {canonical(value.get(it))}
        is String->JSONObject.quote(value)
        else->value.toString()
    }
    @Test fun identicalColdWarmEditedAndHistoryRequests() {
        val path=System.getenv("REPERTOIRE_BENCH_FIXTURE");assumeTrue(path!=null)
        val folder=File(path!!);val cases=JSONObject(File(folder,"workloads.json").readText());val app=RuntimeEnvironment.getApplication()
        val output=JSONArray();val comparison=JSONObject();val phases=JSONArray()
        var queries=0;var decoded=0
        fun reader(ctx:Context)=RepertoireStore(ctx) { file -> SQLiteDatabase.openDatabase(file.path,{_,driver,table,query ->
            queries++;object:CursorWrapper(SQLiteCursor(driver,table,query)) {
                override fun moveToNext():Boolean=super.moveToNext().also {if(it)decoded++}
                override fun moveToFirst():Boolean=super.moveToFirst().also {if(it)decoded++}
            }
        },SQLiteDatabase.OPEN_READONLY) }
        fun run(store:RepertoireStore,request:JSONObject):JSONObject {
            queries=0;decoded=0;val raw=request.toString();val begin=System.nanoTime();val parsed=JSONObject(raw)
            val marker=store.markers(parsed);val markerRaw=marker.toString();val markerEnd=System.nanoTime()
            val result=store.lookup(parsed);val rawResult=result.toString();JSONObject.quote(rawResult);JSONObject.quote(markerRaw)
            val end=System.nanoTime()
            return JSONObject().put("ms",(end-begin)/1e6).put("marker_ms",(markerEnd-begin)/1e6).put("queries",queries).put("rows",decoded)
                .put("response",canonical(result)).put("marker",canonical(marker)).put("bytes",rawResult.toByteArray().size)
        }
        for(mode in listOf("source","main-label","comments1000","add1000","all9-add100")) {
            val directory=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"bench-$mode-").toFile()
            val ctx=object:ContextWrapper(app) {
                override fun getFilesDir()=directory
                override fun getSharedPreferences(name:String,access:Int)=super.getSharedPreferences(directory.name+name,access)
            }
            File(folder,"synthetic/annotations/repertoire.sqlite").copyTo(File(directory,"mobile_repertoire.sqlite"))
            File(directory,"mobile_repertoire_edits.json").writeText(JSONObject(File(folder,"$mode.json").readText()).getJSONObject("edits").toString())
            val initial=reader(ctx);val opened=System.nanoTime();initial.catalog();val prepareMs=(System.nanoTime()-opened)/1e6
            val workloads=listOf(1,9).flatMap { books -> listOf("unchanged","relabelled","anchor","added","new_game").map {books to it} }
            // Warm all code paths; measured positions still get new app caches below.
            for((books,position) in workloads)run(initial,JSONObject(cases.getJSONObject(position).toString()).put("selected",JSONArray((1..books).map {"synthetic_$it"})))
            repeat(7) {trial -> for((books,position) in workloads.shuffled(kotlin.random.Random(trial))) {
                val store=reader(ctx);store.catalog() // Menu/startup cost is reported separately, not charged to a move.
                val req=JSONObject(cases.getJSONObject(position).toString()).put("selected",JSONArray((1..books).map {"synthetic_$it"})).put("intersections",true)
                for(phase in listOf("cold","warm")) {
                    val result=run(store,req)
                    if(trial==0)comparison.put("$mode/$books/$position/$phase",JSONObject().put("response",result.getString("response")).put("marker",result.getString("marker")))
                    result.remove("response");result.remove("marker")
                    output.put(result.put("mode",mode).put("books",books).put("position",position).put("phase",phase).put("trial",trial))
                }
            } }
            val store=reader(ctx);val req=JSONObject(cases.getJSONObject("anchor").toString()).put("selected",JSONArray().put("synthetic_1"))
            run(store,req);val started=System.nanoTime();store.edit(JSONObject(req.toString()).put("id","synthetic_1").put("kind","comment").put("comment","Synthetic benchmark note."));val editMs=(System.nanoTime()-started)/1e6
            val after=run(store,req);val undoStart=System.nanoTime();store.undo(store.undoInfo()!!.getString("token"));val undoMs=(System.nanoTime()-undoStart)/1e6
            val undo=run(store,req)
            phases.put(JSONObject().put("mode",mode).put("prepare_ms",prepareMs).put("disk_bytes",directory.listFiles()!!.sumOf {it.length()}).put("edit_ms",editMs).put("after_edit",after).put("undo_ms",undoMs).put("after_undo",undo))
        }
        System.getenv("REPERTOIRE_BENCH_REFERENCE")?.let { reference ->
            val expected=JSONObject(File(reference).readText()).getJSONObject("comparison")
            assertEquals(expected.length(),comparison.length())
            for(key in expected.keys())assertEquals("Complete response: $key",canonical(expected.getJSONObject(key)),canonical(comparison.getJSONObject(key)))
        }
        System.getenv("REPERTOIRE_BENCH_OUTPUT")?.let {File(it).writeText(JSONObject().put("rows",output).put("phases",phases).put("comparison",comparison).toString())}
    }
}
