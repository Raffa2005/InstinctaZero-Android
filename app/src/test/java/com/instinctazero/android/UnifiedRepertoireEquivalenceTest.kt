package com.instinctazero.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.CursorWrapper
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnifiedRepertoireEquivalenceTest {
    private class Isolated(base:Context,val directory:File):ContextWrapper(base) {
        init { directory.mkdirs() }
        override fun getFilesDir()=directory
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(directory.name+name,mode)
    }
    @Test fun completeResponsesAndBackupsMatchReleasedReaderOnArtificialBooks() {
        val path=System.getenv("UNIFIED_SYNTHETIC_FOLDER");assumeTrue(path!=null)
        val folder=File(path!!);val workloads=JSONObject(File(folder,"workloads.json").readText())
        val app=RuntimeEnvironment.getApplication();val measurements=JSONArray()
        for(mode in listOf("source","main-label","comments1000","add100","add1000","add1000-main","all9-add100")) {
            val oldContext=Isolated(app,File(app.cacheDir,"legacy-$mode"));val newContext=Isolated(app,File(app.cacheDir,"unified-$mode"))
            val snapshot=JSONObject(File(folder,"$mode.json").readText())
            for(context in listOf(oldContext,newContext)) {
                File(folder,"synthetic/annotations/repertoire.sqlite").copyTo(File(context.filesDir,"mobile_repertoire.sqlite"))
                File(context.filesDir,"mobile_repertoire_edits.json").writeText(snapshot.getJSONObject("edits").toString())
            }
            val old=LegacyRepertoireStore(oldContext)
            var queries=0;var rowsRead=0
            val fresh={RepertoireStore(newContext) { file -> SQLiteDatabase.openDatabase(file.path,{_,driver,table,query ->
                queries++;object:CursorWrapper(SQLiteCursor(driver,table,query)) {
                    override fun moveToNext():Boolean=super.moveToNext().also { if(it)rowsRead++ }
                    override fun moveToFirst():Boolean=super.moveToFirst().also { if(it)rowsRead++ }
                }
            },SQLiteDatabase.OPEN_READONLY) }}
            val migrated=fresh();val start=System.nanoTime();migrated.catalog();val migrationMs=(System.nanoTime()-start)/1e6
            assertEquals(UnifiedRepertoireDatabase.canonical(old.backupSnapshot().getJSONObject("edits")),UnifiedRepertoireDatabase.canonical(migrated.backupSnapshot().getJSONObject("edits")))
            for(books in listOf(1,9))for(position in listOf("unchanged","relabelled","anchor","added","new_game")) {
                val req=JSONObject(workloads.getJSONObject(position).toString()).put("selected",JSONArray((1..books).map { "synthetic_$it" })).put("intersections",true)
                val expectedMarker=UnifiedRepertoireDatabase.canonical(old.markers(req));val expected=UnifiedRepertoireDatabase.canonical(old.lookup(req))
                val store=fresh();queries=0;rowsRead=0;val begin=System.nanoTime();val marker=store.markers(req);val result=store.lookup(req);val elapsed=(System.nanoTime()-begin)/1e6
                assertEquals("$mode/$books/$position marker",expectedMarker,UnifiedRepertoireDatabase.canonical(marker))
                assertEquals("$mode/$books/$position complete response",expected,UnifiedRepertoireDatabase.canonical(result))
                measurements.put(JSONObject().put("mode",mode).put("books",books).put("position",position).put("ms",elapsed).put("queries",queries).put("rows",rowsRead).put("migration_ms",migrationMs).put("database_bytes",File(newContext.filesDir,"repertoire_library.sqlite").length()))
            }
            println("Full equivalence $mode passed; migration $migrationMs ms")
        }
        System.getenv("UNIFIED_RESULTS")?.let { File(it).writeText(measurements.toString()) }
    }
}
