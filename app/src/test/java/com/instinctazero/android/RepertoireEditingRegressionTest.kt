package com.instinctazero.android

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepertoireEditingRegressionTest {
    companion object {
        const val PARENT="r3kbnr/1bqp1ppp/p1n1p3/1p6/3NP3/2NBBQ2/PPP2PPP/2KR3R b kq -"
        const val PICTURE="r3kbnr/1bqp1ppp/p1n1p3/8/1p1NP3/2NBBQ2/PPP2PPP/2KR3R w kq -"
    }
    private fun corpus(): File {
        val path=System.getenv("REPERTOIRE_TEST_INDEX");assumeTrue(path!=null)
        return File(path!!).copyTo(File(RuntimeEnvironment.getApplication().filesDir,"mobile_repertoire.sqlite"),overwrite=true)
    }
    private fun pictured(file: File): JSONObject = SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
        var id=db.rawQuery("SELECT id FROM nodes WHERE repertoire_id='taimanov' AND fen=?",arrayOf(PARENT)).use { assertTrue(it.moveToFirst());it.getLong(0) }
        val line=mutableListOf<JSONObject>()
        while(id!=0L)db.rawQuery("SELECT parent_id,uci,san,fen FROM nodes WHERE id=?",arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst());id=if(it.isNull(0))0L else it.getLong(0)
            if(!it.isNull(1))line.add(JSONObject().put("uci",it.getString(1)).put("san",it.getString(2)).put("fen",it.getString(3)))
        }
        line.reverse();line.add(JSONObject().put("uci","b5b4").put("san","b4").put("fen",PICTURE))
        JSONObject().put("root",RepertoireStore.START_POSITION).put("fen",PICTURE).put("intersections",true).put("history",JSONArray(line.map { it.getString("uci") }))
            .put("entries",JSONArray(line)).put("selected",JSONArray().put("taimanov"))
    }
    private fun before(request: JSONObject): JSONObject {
        val r=JSONObject(request.toString());val h=r.getJSONArray("history");val e=r.getJSONArray("entries");h.remove(h.length()-1);e.remove(e.length()-1)
        return r.put("fen",e.getJSONObject(e.length()-1).getString("fen"))
    }
    @Test fun picturedResponseAndContinuationAreSeparateUndoableOperationsWithoutRelabelingBd3() {
        val file=corpus();val request=pictured(file);val parent=before(request);var store=RepertoireStore(RuntimeEnvironment.getApplication())
        fun result(r: JSONObject)=store.lookup(r).getJSONArray("results").getJSONObject(0)
        val sourceComment=result(parent).getJSONArray("comments").toString()
        val old=result(request);assertFalse(old.getBoolean("can_add"));assertTrue(old.getBoolean("can_add_move"));assertTrue(old.getBoolean("add_move_independent"))
        store.edit(JSONObject(request.toString()).put("id","taimanov").put("kind","add_move"))
        assertTrue(result(request).getBoolean("theory"));assertFalse(result(parent).getBoolean("theory"));assertEquals(sourceComment,result(parent).getJSONArray("comments").toString())
        val continuation=JSONObject(request.toString());continuation.getJSONArray("history").put("c3e2")
        val next="r3kbnr/1bqp1ppp/p1n1p3/8/1p1NP3/3BBQ2/PPP1NPPP/2KR3R b kq -"
        continuation.getJSONArray("entries").put(JSONObject().put("san","Ne2").put("fen",next));continuation.put("fen",next)
        assertTrue(result(continuation).getBoolean("can_add"));store.edit(JSONObject(continuation.toString()).put("id","taimanov").put("kind","add"))
        assertTrue(result(continuation).getBoolean("theory"))
        val deletion=JSONObject(parent.toString()).put("history",request.getJSONArray("history")).put("id","taimanov").put("kind","delete")
        store.edit(deletion);store=RepertoireStore(RuntimeEnvironment.getApplication())
        val deletedParent=result(parent);val deletedCurrent=result(request)
        assertFalse(result(request).getBoolean("theory"));assertFalse(result(continuation).getBoolean("theory"));assertEquals(1,result(parent).getJSONArray("deleted_moves").length())
        store.undo(store.undoInfo()!!.getString("token"));assertTrue(result(request).getBoolean("theory"));assertTrue(result(continuation).getBoolean("theory"))
        store.edit(deletion);store.edit(JSONObject(deletion.toString()).put("kind","restore_move"));assertTrue(result(continuation).getBoolean("theory"))
        assertFalse(result(parent).getBoolean("theory"));assertEquals(sourceComment,result(parent).getJSONArray("comments").toString())
        System.getenv("REPERTOIRE_EDIT_PREVIEW")?.let { output -> File(output).writeText(JSONObject().put("request",request).put("initial",old).put("parent",result(parent)).put("deleted_parent",deletedParent).put("deleted_current",deletedCurrent).put("added",result(request)).put("continuation",result(continuation)).toString()) }
    }
    @Test fun realCorpusColdWarmAndRapidNavigationMeasurements() {
        val output=System.getenv("REPERTOIRE_EDIT_BENCH_OUTPUT");assumeTrue(output!=null)
        val file=corpus();val base=pictured(file);val rows=JSONArray()
        val ids=RepertoireStore(RuntimeEnvironment.getApplication()).catalog().getJSONArray("repertoires").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        for(selected in listOf(listOf("taimanov"),ids))for(ply in listOf(0,1,16,17,18)) {
            val request=JSONObject(base.toString()).put("history",JSONArray((0 until ply).map { base.getJSONArray("history").getString(it) }))
                .put("entries",JSONArray((0 until ply).map { base.getJSONArray("entries").getJSONObject(it) }))
                .put("fen",if(ply==0)RepertoireStore.START_POSITION else base.getJSONArray("entries").getJSONObject(ply-1).getString("fen")).put("selected",JSONArray(selected)).put("intersections",false)
            val store=RepertoireStore(RuntimeEnvironment.getApplication());val samples=JSONArray();val markerSamples=JSONArray()
            repeat(7){val start=System.nanoTime();val marker=store.markers(request);markerSamples.put((System.nanoTime()-start)/1e6)
                val result=store.lookup(request);samples.put((System.nanoTime()-start)/1e6);assertEquals(selected.size,result.getJSONArray("results").length())
                for(i in 0 until selected.size)assertEquals(result.getJSONArray("results").getJSONObject(i).getBoolean("theory"),marker.getJSONArray("results").getJSONObject(i).getBoolean("theory"))
            }
            rows.put(JSONObject().put("ply",ply).put("selections",selected.size).put("samples_ms",samples).put("marker_ms",markerSamples))
        }
        File(output!!).writeText(rows.toString())
    }
}
