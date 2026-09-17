package com.instinctazero.android

import android.content.Context
import android.content.ContextWrapper
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

/** Opt-in scoped local acceptance. Inputs are read-only and never logged or committed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnifiedRepertoireAcceptanceTest {
    private fun context():Context {
        val app=RuntimeEnvironment.getApplication()
        val folder=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"acceptance-").toFile()
        return object:ContextWrapper(app) {
            override fun getFilesDir()=folder
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(folder.name+name,mode)
        }
    }
    private fun same(label:String,expected:Any?,actual:Any?) {
        // Full canonical responses, including all notes, without exposing notes in failures.
        fun digest(value:Any?)=RepertoireStore.hash(UnifiedRepertoireDatabase.canonical(value).toByteArray())
        assertEquals(label,digest(expected),digest(actual))
    }
    @Test fun actualMigrationAndCurrentBackupRollbackMatchReleasedReaderCompletely() {
        val sourcePath=System.getenv("REPERTOIRE_TEST_INDEX")
        val nextPath=System.getenv("REPERTOIRE_REFRESH_INDEX")
        val backupPath=System.getenv("REPERTOIRE_PERSONAL_TEST_BACKUP")
        val casesPath=System.getenv("REPERTOIRE_TEST_CASES")
        assumeTrue(listOf(sourcePath,nextPath,backupPath,casesPath).all {it!=null})
        val source=File(sourcePath!!);val next=File(nextPath!!)
        val snapshot=JSONObject(File(backupPath!!).readText())
        val ctx=context();source.copyTo(File(ctx.filesDir,"mobile_repertoire.sqlite"))
        val legacy=LegacyRepertoireStore(ctx);legacy.restoreBackup(snapshot)
        val original=File(ctx.filesDir,"mobile_repertoire_edits.json").readBytes()
        val requests=linkedMapOf<String,JSONObject>()
        val cases=JSONArray(File(casesPath!!).readText())
        for(i in 0 until cases.length()) {
            val row=JSONObject(cases.getJSONObject(i).toString()).put("selected",JSONArray().put(cases.getJSONObject(i).getString("rep")))
            requests["source:$i"]=row
        }
        val edits=legacy.backupSnapshot().getJSONObject("edits")
        for(rep in edits.keys())if(!rep.startsWith('_'))for(key in edits.getJSONObject(rep).keys()) {
            val entry=edits.getJSONObject(rep).getJSONObject(key)
            for(field in listOf("before","fen"))entry.optString(field).takeIf {it.isNotBlank()}?.let {raw ->
                val fen=RepertoireStore.position(raw)
                requests["$rep:$fen"]=JSONObject().put("root",fen).put("fen",fen).put("history",JSONArray()).put("entries",JSONArray()).put("selected",JSONArray().put(rep)).put("intersections",true)
            }
        }
        var store=RepertoireStore(ctx)
        requests.values.forEachIndexed {i,request ->same("Migrated marker $i",legacy.markers(request),store.markers(request));same("Migrated full response $i",legacy.lookup(request),store.lookup(request))}
        same("All migrated personal fields",legacy.backupSnapshot().getJSONObject("edits"),store.backupSnapshot().getJSONObject("edits"))
        assertArrayEquals(original,File(ctx.filesDir,"mobile_repertoire_edits.json").readBytes())
        val saved=store.backupSnapshot()
        next.inputStream().use {store.install(it,RepertoireStore.hash(next.readBytes()))}
        store=RepertoireStore(ctx)
        val rollbackContext=context();next.copyTo(File(rollbackContext.filesDir,"mobile_repertoire.sqlite"))
        val rollback=LegacyRepertoireStore(rollbackContext);rollback.restoreBackup(store.backupSnapshot())
        val replacement=RepertoireStore(context());next.inputStream().use {replacement.install(it,RepertoireStore.hash(next.readBytes()))};replacement.restoreBackup(store.backupSnapshot())
        requests.values.forEachIndexed {i,request ->
            same("Refreshed full response/old-reader rollback $i",rollback.lookup(request),store.lookup(request))
            same("Replacement phone restore $i",store.lookup(request),replacement.lookup(request))
        }
        store.undoInfo()?.getString("token")?.let { token ->
            store.undo(token);rollback.undo(token)
            requests.values.forEachIndexed {i,request ->same("Undo after refresh $i",rollback.lookup(request),store.lookup(request))}
        }
        store.restoreBackup(saved)
        same("All restored personal fields",saved.getJSONObject("edits"),store.backupSnapshot().getJSONObject("edits"))
        assertArrayEquals(original,File(ctx.filesDir,"mobile_repertoire_edits.json").readBytes())
        println("Unified migration/refresh/restart/Undo/replacement/old-reader rollback matched complete responses at ${requests.size} positions. Inputs unchanged.")
    }
}
