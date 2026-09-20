package com.instinctazero.android

import android.content.Context
import java.io.File
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
class StudyDatabaseTest {
    private val app=RuntimeEnvironment.getApplication()
    private fun directory()=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"board-save-").toFile()
    private fun prefs(dir:File)=app.getSharedPreferences(dir.name,Context.MODE_PRIVATE)
    private fun meta(edited:Boolean=false)=JSONObject().put("v",2).put("editedPosition",edited).put("gameId",JSONObject.NULL)
        .put("initialFen",RepertoireStore.START_POSITION+" 0 1").put("cursor",JSONArray())
    private fun patch(revision:Long,vararg branches:JSONObject)=JSONObject().put("revision",revision).put("meta",meta()).put("branches",JSONArray(branches.toList())).toString()
    private fun branch(parent:Int,vararg children:Pair<Int,String>)=JSONObject().put("parent",parent).put("children",JSONArray(children.map {JSONObject().put("id",it.first).put("u",it.second)}))
    @Test fun importsEveryLegacyNodeBeyondBothFormerLimitsAndKeepsRecoveryCopy() {
        val dir=directory();val prefs=prefs(dir)
        // Wide is deliberate: >512 total nodes without >512 ply on any one route.
        val children=JSONArray((1..15000).map {JSONObject().put("u","e2e4").put("c",JSONArray())})
        val legacy=meta().put("v",1).put("tree",children).toString()
        assertTrue(legacy.length>256*1024)
        assertTrue(prefs.edit().putString("state_v1",legacy).commit())
        var store=StudyDatabase(dir,prefs)
        assertEquals(15000,JSONObject(store.current()).getJSONArray("nodes").length())
        assertTrue(JSONObject(store.save(patch(0,branch(15000,15001 to "e7e5")))).getBoolean("saved"))
        store=StudyDatabase(dir,prefs)
        assertEquals(15001,JSONObject(store.current()).getJSONArray("nodes").length())
        assertTrue(JSONObject(store.save(patch(1))).getBoolean("saved")) // navigation has no graph writes
        assertEquals(15001,JSONObject(StudyDatabase(dir,prefs).current()).getJSONArray("nodes").length())
        assertEquals(legacy,prefs.getString("state_v1",null))
        assertEquals(legacy,JSONObject(File(dir,"analysis-legacy-before-migration.json").readText()).getString("study"))
    }
    @Test fun appendPromoteDeleteAndScratchBoardAreDurableAndIndependent() {
        val dir=directory();val prefs=prefs(dir);var store=StudyDatabase(dir,prefs)
        assertTrue(JSONObject(store.save(patch(0,branch(0,1 to "e2e4",2 to "d2d4"),branch(1,3 to "e7e5"),branch(2,4 to "d7d5")))).getBoolean("saved"))
        assertTrue(JSONObject(store.save(patch(1,branch(0,2 to "d2d4",1 to "e2e4")))).getBoolean("saved"))
        store=StudyDatabase(dir,prefs)
        assertEquals(2,JSONObject(store.current()).getJSONArray("nodes").getJSONObject(0).getInt("id"))
        val source=store.current()
        val scratch=JSONObject(patch(0,branch(0,1 to "a2a4"))).put("meta",meta(true)).toString()
        assertTrue(JSONObject(store.save(scratch)).getBoolean("saved"));assertEquals(source,store.current(source=true))
        assertTrue(JSONObject(store.save(patch(2,branch(0,1 to "e2e4")))).getBoolean("saved"))
        val remaining=JSONObject(StudyDatabase(dir,prefs).current()).getJSONArray("nodes")
        assertEquals(setOf(1,3),(0 until remaining.length()).map {remaining.getJSONObject(it).getInt("id")}.toSet())
    }
    @Test fun failuresAndStaleWritersCannotAcknowledgeOrReplaceData() {
        val dir=directory();val prefs=prefs(dir);val store=StudyDatabase(dir,prefs)
        assertTrue(JSONObject(store.save(patch(0,branch(0,1 to "e2e4")))).getBoolean("saved"))
        val original=store.current()
        assertFalse(JSONObject(store.save(patch(0,branch(0,2 to "d2d4")))).getBoolean("saved"))
        val failing=StudyDatabase(dir,prefs) {throw java.io.IOException("disk failed")}
        assertFalse(JSONObject(failing.save(patch(1,branch(1,2 to "e7e5")))).getBoolean("saved"))
        assertEquals(original,StudyDatabase(dir,prefs).current())
        assertTrue(JSONObject(store.save(patch(1,branch(1,2 to "e7e5")))).getBoolean("saved"))
        assertEquals(2,JSONObject(StudyDatabase(dir,prefs).current()).getJSONArray("nodes").length())
    }
    @Test fun failedMigrationRetainsPreferencesAndBackupAndRetriesWithoutPartialTree() {
        val dir=directory();val prefs=prefs(dir)
        val legacy=meta().put("v",1).put("tree",JSONArray().put(JSONObject().put("u","e2e4").put("c",JSONArray()))).toString()
        prefs.edit().putString("state_v1",legacy).commit()
        assertTrue(runCatching {StudyDatabase(dir,prefs) {throw java.io.IOException()}.current()}.isFailure)
        assertEquals(legacy,prefs.getString("state_v1",null))
        assertEquals(1,JSONObject(StudyDatabase(dir,prefs).current()).getJSONArray("nodes").length())
    }
}
