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

/** Run write/read in separate Gradle/JVM invocations against a dedicated temp folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StudyDatabaseProcessTest {
    @Test fun actualProcessBoundary() {
        val path=System.getenv("STUDY_PROCESS_FOLDER");assumeTrue(path!=null)
        val folder=File(path!!);require(folder.isDirectory && folder.name.startsWith("iz-study-process-"))
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("isolated-process-board",Context.MODE_PRIVATE)
        val store=StudyDatabase(folder,prefs)
        if(System.getenv("STUDY_PROCESS_PHASE")=="write") {
            require(!File(folder,"analysis_boards.sqlite").exists())
            val moves=(0 until 1600).map {listOf("g1f3","g8f6","f3g1","f6g8")[it%4]}
            val branches=JSONArray(moves.mapIndexed {i,uci ->JSONObject().put("parent",i).put("children",JSONArray().put(JSONObject().put("id",i+1).put("u",uci)))})
            val meta=JSONObject().put("v",2).put("initialFen",RepertoireStore.START_POSITION+" 0 1").put("cursor",JSONArray(moves)).put("gameId",JSONObject.NULL)
            assertTrue(JSONObject(store.save(JSONObject().put("revision",0).put("replace",true).put("meta",meta).put("branches",branches).toString())).getBoolean("saved"))
        } else {
            val saved=JSONObject(store.current());assertEquals(1600,saved.getJSONArray("nodes").length());assertEquals(1600,saved.getJSONArray("cursor").length());assertEquals(1,saved.getInt("revision"))
        }
    }
}
