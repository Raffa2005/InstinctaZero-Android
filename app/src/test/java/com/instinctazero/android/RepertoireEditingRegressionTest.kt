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
    private fun prefix(r: JSONObject, ply: Int): JSONObject = JSONObject(r.toString())
        .put("history",JSONArray((0 until ply).map { r.getJSONArray("history").getString(it) }))
        .put("entries",JSONArray((0 until ply).map { r.getJSONArray("entries").getJSONObject(it) }))
        .put("fen",if(ply==0)RepertoireStore.START_POSITION else r.getJSONArray("entries").getJSONObject(ply-1).getString("fen"))
    private fun extend(request: JSONObject): JSONObject = JSONObject(request.toString()).also { r ->
        for((uci,san,fen) in listOf(
            listOf("c3e2","Nce2","r3kbnr/1bqp1ppp/p1n1p3/8/1p1NP3/3BBQ2/PPP1NPPP/2KR3R b kq -"),
            listOf("g8f6","Nf6","r3kb1r/1bqp1ppp/p1n1pn2/8/1p1NP3/3BBQ2/PPP1NPPP/2KR3R w kq -"),
            listOf("c1b1","Kb1","r3kb1r/1bqp1ppp/p1n1pn2/8/1p1NP3/3BBQ2/PPP1NPPP/1K1R3R b kq -"),
            listOf("f8e7","Be7","r3k2r/1bqpbppp/p1n1pn2/8/1p1NP3/3BBQ2/PPP1NPPP/1K1R3R w kq -"))) {
            r.getJSONArray("history").put(uci);r.getJSONArray("entries").put(JSONObject().put("uci",uci).put("san",san).put("fen",fen));r.put("fen",fen)
        }
    }
    @Test fun picturedWholeLineSavesDirectlyThroughInformationExactlyLikeRegularTheory() {
        val file=corpus();val request=pictured(file);val parent=before(request);var store=RepertoireStore(RuntimeEnvironment.getApplication())
        val sourceHash=RepertoireStore.hash(file.readBytes())
        fun result(r: JSONObject)=store.lookup(r).getJSONArray("results").getJSONObject(0)
        fun add(r: JSONObject)=store.edit(JSONObject(r.toString()).put("id","taimanov").put("kind","add"))
        val sourceComment=result(parent).getJSONArray("comments").toString()
        val continuation=extend(request)
        fun states()=JSONArray((17..22).map { result(prefix(continuation,it)) })
        val initial=states();val old=result(request)
        assertTrue(old.getBoolean("can_add"));assertEquals(1,old.getInt("add_count"));assertTrue(result(continuation).getBoolean("can_add"));assertEquals(5,result(continuation).getInt("add_count"))
        // The first and only write is the full ordinary UI save, four plies beyond b4.
        add(continuation);val added=states();val backup=store.backupSnapshot()
        assertFalse(result(parent).getBoolean("theory"))
        for(ply in 18..22)assertTrue(result(prefix(continuation,ply)).getBoolean("theory"))
        store=RepertoireStore(RuntimeEnvironment.getApplication());assertEquals(added.toString(),states().toString())
        store.undo(store.undoInfo()!!.getString("token"));assertEquals(initial.toString(),states().toString())
        // Compare with extending an already regular parent. Same resulting entries,
        // recommendations, comments and markers; no informational-only presentation.
        add(parent);val regularInitial=states();add(continuation);val regularAdded=states()
        for(i in 1..5)for(key in listOf("theory","kind","alternative","moves","comments","end_of_line"))
            assertEquals(added.getJSONObject(i).get(key).toString(),regularAdded.getJSONObject(i).get(key).toString())
        store.restoreBackup(backup);assertEquals(added.toString(),states().toString())
        val alternate=JSONObject(continuation.toString())
        val reordered=listOf(
            Triple("b1c3","Nc3","rnbqkbnr/pp1ppppp/8/2p5/4P3/2N5/PPPP1PPP/R1BQKBNR b KQkq -"),
            Triple("e7e6","e6","rnbqkbnr/pp1p1ppp/4p3/2p5/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq -"),
            Triple("g1f3","Nf3","rnbqkbnr/pp1p1ppp/4p3/2p5/4P3/2N2N2/PPPP1PPP/R1BQKB1R b KQkq -"),
            Triple("b8c6","Nc6","r1bqkbnr/pp1p1ppp/2n1p3/2p5/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq -"),
            Triple("d2d4","d4","r1bqkbnr/pp1p1ppp/2n1p3/2p5/3PP3/2N2N2/PPP2PPP/R1BQKB1R b KQkq -"),
            Triple("c5d4","cxd4","r1bqkbnr/pp1p1ppp/2n1p3/8/3pP3/2N2N2/PPP2PPP/R1BQKB1R w KQkq -"),
            Triple("f3d4","Nxd4","r1bqkbnr/pp1p1ppp/2n1p3/8/3NP3/2N5/PPP2PPP/R1BQKB1R b KQkq -"))
        reordered.forEachIndexed { i,(uci,san,fen) -> alternate.getJSONArray("history").put(i+2,uci);alternate.getJSONArray("entries").put(i+2,JSONObject().put("uci",uci).put("san",san).put("fen",fen)) }
        for(ply in 18..22)for(key in listOf("theory","moves","comments","end_of_line"))
            assertEquals(result(prefix(continuation,ply)).get(key).toString(),result(prefix(alternate,ply)).get(key).toString())
        // Both the old-order and transposed-order save remain eligible after Undo.
        store.undo(store.undoInfo()!!.getString("token"));assertTrue(result(alternate).getBoolean("can_add"));add(alternate)
        assertEquals(added.toString(),states().toString())
        val excludeParent=JSONObject(before(parent).toString()).put("history",parent.getJSONArray("history")).put("id","taimanov").put("kind","analysis")
        store.edit(excludeParent);assertFalse(result(continuation).getBoolean("theory"));assertFalse(result(continuation).getBoolean("can_add"))
        assertThrows(IllegalArgumentException::class.java){add(continuation)}
        store.undo(store.undoInfo()!!.getString("token"));assertTrue(result(continuation).getBoolean("theory"))
        // A board started directly at the resulting position gets the same book facts.
        val transposed=JSONObject(continuation.toString()).put("root",PICTURE)
            .put("history",JSONArray((18..21).map { continuation.getJSONArray("history").getString(it) }))
            .put("entries",JSONArray((18..21).map { continuation.getJSONArray("entries").getJSONObject(it) }))
        assertTrue(result(transposed).getBoolean("theory"));assertEquals(result(continuation).getJSONArray("comments").toString(),result(transposed).getJSONArray("comments").toString())
        val deletion=JSONObject(parent.toString()).put("history",request.getJSONArray("history")).put("id","taimanov").put("kind","delete")
        store.edit(deletion);store=RepertoireStore(RuntimeEnvironment.getApplication())
        val deletedParent=result(parent);val deletedCurrent=result(request)
        assertFalse(result(request).getBoolean("theory"));assertFalse(result(continuation).getBoolean("theory"));assertEquals(1,result(parent).getJSONArray("deleted_moves").length())
        assertFalse(result(continuation).getBoolean("can_add"));assertThrows(IllegalArgumentException::class.java){add(continuation)}
        store.undo(store.undoInfo()!!.getString("token"));assertTrue(result(request).getBoolean("theory"));assertTrue(result(continuation).getBoolean("theory"))
        store.edit(deletion);store.edit(JSONObject(deletion.toString()).put("kind","restore_move"));assertTrue(result(continuation).getBoolean("theory"))
        assertEquals(sourceComment,result(parent).getJSONArray("comments").toString())
        assertEquals(sourceHash,RepertoireStore.hash(file.readBytes()))
        System.getenv("REPERTOIRE_EDIT_PREVIEW")?.let { output -> File(output).writeText(JSONObject().put("request",request).put("extension",continuation)
            .put("unified",JSONObject().put("initial",initial).put("added",added).put("regular_initial",regularInitial).put("regular_added",regularAdded))
            .put("initial",old).put("parent",result(parent)).put("deleted_parent",deletedParent).put("deleted_current",deletedCurrent).put("added",result(request)).put("continuation",result(continuation)).toString()) }
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
