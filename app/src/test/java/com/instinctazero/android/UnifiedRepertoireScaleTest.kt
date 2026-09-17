package com.instinctazero.android

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

/** Large public generated corpus. RSS includes the host/Robolectric runtime, not phone RAM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnifiedRepertoireScaleTest {
    @Test fun largeMigrationGraphEditRefreshAndRestartRemainBounded() {
        val path=System.getenv("UNIFIED_STRESS_FOLDER");assumeTrue(path!=null)
        val fixture=File(path!!);val app=RuntimeEnvironment.getApplication()
        val directory=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"large-unified-").toFile()
        val context=object:ContextWrapper(app) {
            override fun getFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(directory.name+name,mode)
        }
        val source=File(fixture,"synthetic/annotations/repertoire.sqlite")
        source.copyTo(File(directory,"mobile_repertoire.sqlite"))
        File(directory,"mobile_repertoire_edits.json").writeText(JSONObject(File(fixture,"add1000.json").readText()).getJSONObject("edits").toString())
        val cases=JSONObject(File(fixture,"workloads.json").readText())
        val running=AtomicBoolean(true);var peakRss=0L;var peakBytes=0L
        fun rss()=File("/proc/self/status").takeIf {it.isFile}?.readLines()?.firstOrNull {it.startsWith("VmRSS:")}?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.times(1024) ?: 0
        val initialRss=rss()
        val sampler=Thread {
            while(running.get()) {
                peakRss=maxOf(peakRss,rss());peakBytes=maxOf(peakBytes,directory.listFiles().orEmpty().sumOf {it.length()})
                Thread.sleep(10)
            }
        }.apply {isDaemon=true;start()}
        val result=JSONObject().put("source_bytes",source.length()).put("initial_host_rss",initialRss)
        fun time(block:()->Unit):Double {val start=System.nanoTime();block();return (System.nanoTime()-start)/1e6}
        try {
            var store=RepertoireStore(context)
            result.put("migration_ms",time {assertEquals(9,store.catalog().getJSONArray("repertoires").length())})
            val req=JSONObject(cases.getJSONObject("added").toString()).put("selected",JSONArray((1..9).map {"synthetic_$it"}))
            val expected=UnifiedRepertoireDatabase.canonical(store.lookup(req))
            result.put("new_game_ms",time {store.markers(cases.getJSONObject("new_game"));store.lookup(cases.getJSONObject("new_game"))})
            val move=JSONObject(cases.getJSONObject("next_added").toString()).put("id","synthetic_1").put("kind","delete").put("fen",req.getString("fen"))
            result.put("graph_edit_ms",time {store.edit(move)})
            System.getenv("UNIFIED_STRESS_DATABASE")?.let {File(directory,"repertoire_library.sqlite").copyTo(File(it),overwrite=true)}
            val saved=UnifiedRepertoireDatabase.canonical(store.backupSnapshot())
            result.put("refresh_ms",time {source.inputStream().use {store.install(it,RepertoireStore.hash(source.readBytes()))}})
            store=RepertoireStore(context)
            // Source fingerprint changes from the legacy fixture's empty metadata, not edits.
            assertEquals(JSONObject(saved).getJSONObject("edits").toString(),JSONObject(UnifiedRepertoireDatabase.canonical(store.backupSnapshot())).getJSONObject("edits").toString())
            result.put("undo_ms",time {store.undo(store.undoInfo()!!.getString("token"))})
            assertEquals(expected,UnifiedRepertoireDatabase.canonical(store.lookup(req)))
            result.put("database_bytes",File(directory,"repertoire_library.sqlite").length())
            result.put("retained_legacy_bytes",File(directory,"mobile_repertoire.sqlite").length()+File(directory,"mobile_repertoire_edits.json").length())
        } finally {running.set(false);sampler.join()}
        result.put("peak_host_rss",peakRss).put("peak_working_directory_bytes",peakBytes)
        System.getenv("UNIFIED_STRESS_OUTPUT")?.let {File(it).writeText(result.toString())}
        println(result)
    }
}
