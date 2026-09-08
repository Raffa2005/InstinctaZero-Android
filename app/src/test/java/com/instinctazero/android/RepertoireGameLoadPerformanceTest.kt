package com.instinctazero.android

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

/** Opt-in real-corpus end-of-game/edited-position checks; never a handset speed claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepertoireGameLoadPerformanceTest {
    private fun canonical(value: Any?): String = when(value) {
        null,JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    @Test fun finalGamePositionAndEditedHistoryRemainExactAcrossCacheAndUndo() {
        val index=System.getenv("REPERTOIRE_TEST_INDEX");val workloads=System.getenv("REPERTOIRE_GAME_LOAD_CASES");val output=System.getenv("REPERTOIRE_GAME_LOAD_OUTPUT")
        assumeTrue(index!=null && workloads!=null && output!=null)
        val context=RuntimeEnvironment.getApplication()
        File(index!!).copyTo(File(context.filesDir,"mobile_repertoire.sqlite"),overwrite=true)
        val cases=JSONArray(File(workloads!!).readText());val results=JSONArray()
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i);val request=case.getJSONObject("request");val raw=request.toString()
            var store=RepertoireStore(context)
            val before=canonical(store.lookup(JSONObject(raw)))
            fun measure(phase: String) {
                store=RepertoireStore(context);val samples=JSONArray();var response=""
                repeat(9) {
                    val start=System.nanoTime();response=store.lookup(JSONObject(raw)).toString();samples.put((System.nanoTime()-start)/1e6)
                }
                results.put(JSONObject().put("name",case.getString("name")).put("phase",phase).put("samples_ms",samples).put("response",response))
            }
            measure("original")
            if(case.getString("name").startsWith("edited")) {
                val previous=request.getJSONArray("entries").getJSONObject(request.getJSONArray("history").length()-2).getString("fen")
                store.edit(JSONObject(raw).put("id","taimanov").put("kind","analysis").put("fen",previous))
                measure("excluded")
                val rows=store.lookup(JSONObject(raw)).getJSONArray("results")
                assertFalse((0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id")=="taimanov" }.getBoolean("theory"))
                store.undo(store.undoInfo()!!.getString("token"));measure("undo")
                assertEquals(before,canonical(store.lookup(JSONObject(raw))))
            }
        }
        System.getenv("REPERTOIRE_GAME_LOAD_REFERENCE")?.let { reference ->
            val previous=JSONArray(File(reference).readText());assertEquals(previous.length(),results.length())
            for(i in 0 until results.length())assertEquals("Full game-load response $i",canonical(JSONObject(previous.getJSONObject(i).getString("response"))),canonical(JSONObject(results.getJSONObject(i).getString("response"))))
        }
        File(output!!).writeText(results.toString())
    }
}
