package com.instinctazero.android

import android.content.Context
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

/** Replay the real touch-controller's deltas through Android SQLite, including a
 * separate JVM read phase. Only synthetic fixtures and dedicated temp files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClickInputPersistenceTest {
    @Test fun restoredCodeCanSavePriorReleaseDatabaseWithoutTouchingOtherData() {
        assumeTrue(System.getenv("CLICK_CHESS_PROCESS_PHASE")=="read")
        val path=System.getenv("CLICK_CHESS_PROCESS_FOLDER");assumeTrue(path!=null)
        val base=File(path!!);require(base.isDirectory && base.name.startsWith("iz-click-process-"))
        val app=RuntimeEnvironment.getApplication()
        val inputs=base.listFiles()!!.filter {it.isDirectory && it.name.startsWith("${android.os.Build.VERSION.SDK_INT}-")}
        assertTrue(inputs.size>=12)
        for(input in inputs) {
            // Only writable copies of synthetic previous-release databases. No live data.
            val copy=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"rollback-compat-").toFile()
            input.listFiles()!!.filter {it.isFile}.forEach {it.copyTo(File(copy,it.name))}
            val preserved=listOf("local-game.pgn","archive-sentinel","repertoire-sentinel").associateWith {name ->
                File(copy,name).also {it.writeText("Synthetic preserved $name")}.readBytes()
            }
            val prefs=app.getSharedPreferences(copy.name,Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString("pairing-sentinel","synthetic-only").putBoolean("privacy",true).commit())
            val preferences=prefs.all.toMap()
            val before=JSONObject(StudyDatabase(copy,prefs).current())
            val meta=JSONObject(before.toString()).apply {remove("nodes");remove("revision");put("title","Rollback save check")}
            val saved=JSONObject(StudyDatabase(copy,prefs).save(JSONObject().put("revision",before.getLong("revision"))
                .put("meta",meta).put("branches",JSONArray()).toString()))
            assertTrue(saved.getBoolean("saved"))
            val reopened=JSONObject(StudyDatabase(copy,prefs).current())
            assertEquals(before.getLong("revision")+1,reopened.getLong("revision"))
            assertEquals("Rollback save check",reopened.getString("title"))
            for(key in listOf("nodes","cursor","initialFen","black","tab"))assertEquals(before.get(key).toString(),reopened.get(key).toString())
            preserved.forEach {(name,bytes)->assertArrayEquals(bytes,File(copy,name).readBytes())}
            assertEquals(preferences,prefs.all)
        }
    }

    @Test fun touchCorrectionsSurviveNativeSaveAndProcessReopen() {
        val source=System.getenv("CLICK_CHESS_TRACES");assumeTrue(source!=null)
        val base=File(requireNotNull(System.getenv("CLICK_CHESS_PROCESS_FOLDER")))
        require(base.isDirectory && base.name.startsWith("iz-click-process-"))
        val readOnly=System.getenv("CLICK_CHESS_PROCESS_PHASE")=="read"
        val fixtures=JSONArray(File(source!!).readText())
        assertTrue(fixtures.length()>=12)
        for(i in 0 until fixtures.length()) {
            val fixture=fixtures.getJSONObject(i)
            val dir=File(base,"${android.os.Build.VERSION.SDK_INT}-$i")
            val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("click-$i",Context.MODE_PRIVATE)
            if(!readOnly) {
                check(dir.mkdir()) { "Refusing to overwrite test state" }
                assertTrue(prefs.edit().putString("state_v1",fixture.getJSONObject("initial").toString()).commit())
                val requests=fixture.getJSONArray("requests")
                for(j in 0 until requests.length()) {
                    val saved=JSONObject(StudyDatabase(dir,prefs).save(requests.getJSONObject(j).toString()))
                    assertTrue("${fixture.getString("name")} delta $j",saved.getBoolean("saved"))
                }
            }
            check(File(dir,"analysis_boards.sqlite").isFile)
            val actual=JSONObject(StudyDatabase(dir,prefs).current())
            val expected=fixture.getJSONObject("expected")
            for(key in listOf("cursor","initialFen","revision","black","tab"))
                assertEquals("${fixture.getString("name")} $key",expected.get(key).toString(),actual.get(key).toString())
            fun nodes(json:JSONObject):List<String> {
                val rows=json.getJSONArray("nodes")
                return (0 until rows.length()).map { j -> val n=rows.getJSONObject(j)
                    listOf("id","p","o","u").joinToString(":") { n.get(it).toString() }
                }.sorted()
            }
            assertEquals(fixture.getString("name"),nodes(expected),nodes(actual))
            assertTrue(File(dir,"analysis-legacy-before-migration.json").isFile)
        }
    }
}
