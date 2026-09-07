package com.instinctazero.android

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class RepertoireAuthoringTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val fixture = JSONObject(javaClass.getResourceAsStream("/qga-transposition.json")!!.bufferedReader().readText())
    private fun create(store: RepertoireStore, name: String = "My Black repertoire") = store.saveRepertoire(JSONObject().put("name",name).put("side","black")).getString("created_id")
    private fun request(id: String, name: String = "reported", ply: Int = 16): JSONObject {
        val line=fixture.getJSONObject(name);val entries=JSONArray((0 until ply).map { line.getJSONArray("entries").getJSONObject(it) })
        return JSONObject().put("root",line.getString("root")).put("history",JSONArray((0 until ply).map { line.getJSONArray("history").getString(it) }))
            .put("entries",entries).put("fen",if(ply==0)RepertoireStore.START_POSITION else entries.getJSONObject(ply-1).getString("fen")).put("selected",JSONArray().put(id))
    }
    private fun result(store: RepertoireStore, request: JSONObject) = store.lookup(request).getJSONArray("results").getJSONObject(0)
    @Test fun createWithoutDownloadExtendTransposeCommentRenameAndUndoSurviveRestart() {
        var store=RepertoireStore(context)
        assertFalse(store.catalog().getBoolean("installed"))
        val id=create(store)
        assertTrue(store.catalog().getBoolean("installed")); assertFalse(File(context.filesDir,"mobile_repertoire.sqlite").exists())
        assertTrue(result(store,request(id,ply=0)).getBoolean("end_of_line"))
        val line=request(id)
        assertEquals(16,result(store,line).getInt("add_count"))
        store.edit(JSONObject(line.toString()).put("id",id).put("kind","add"))
        assertTrue(result(store,line).getBoolean("theory"))
        assertTrue(result(store,request(id,"canonical")).getBoolean("theory"))
        val comment="Use the open file.\n\n<Not HTML> ♞"
        store.edit(JSONObject(line.toString()).put("id",id).put("kind","comment").put("comment",comment))
        assertEquals(comment,result(store,request(id,"canonical")).getJSONArray("comments").getString(0))
        val token=store.undoInfo()!!.getString("token")
        store.saveRepertoire(JSONObject().put("id",id).put("name","Renamed black repertoire"))
        store=RepertoireStore(context)
        assertEquals("black",result(store,line).getString("side"))
        assertEquals("Renamed black repertoire",result(store,line).getString("name"))
        assertEquals(token,store.undoInfo()!!.getString("token"))
        store.undo(token)
        assertTrue(result(store,line).getBoolean("theory")); assertEquals(0,result(store,line).getJSONArray("comments").length())
        assertEquals("Renamed black repertoire",result(store,line).getString("name"))
    }
    @Test fun creatingAnotherRepertoirePreservesLastLineUndoAndValidatesMetadata() {
        val store=RepertoireStore(context); val id=create(store)
        store.edit(request(id,ply=2).put("id",id).put("kind","add"))
        val token=store.undoInfo()!!.getString("token")
        val other=create(store,"Another repertoire")
        store.undo(token)
        assertFalse(result(store,request(id,ply=2)).getBoolean("theory"))
        assertTrue(result(store,request(other,ply=0)).getBoolean("theory"))
        for(bad in listOf("", " ", "a".repeat(81))) assertThrows(IllegalArgumentException::class.java) { create(store,bad) }
        assertThrows(IllegalArgumentException::class.java) { store.saveRepertoire(JSONObject().put("name","Invalid").put("side","purple")) }
        assertEquals(2,store.catalog().getJSONArray("repertoires").length())
    }
}
