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

/** Opt-in measurements on the private corpus. No corpus or personal comments are bundled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepertoirePerformanceTest {
    private fun canonical(value: Any?): String = when(value) {
        null,JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    @Test fun indexedChildrenEqualCompleteParentJoinRowsAcrossTheCorpus() {
        val index = System.getenv("REPERTOIRE_TEST_INDEX")
        assumeTrue(index != null)
        SQLiteDatabase.openDatabase(index!!,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            // This invariant proves equivalence for EVERY source edge, not just a sample:
            // every non-root child belongs to the same repertoire and stores its parent's FEN.
            db.rawQuery("SELECT count(*) FROM nodes n LEFT JOIN nodes p ON p.id=n.parent_id " +
                "WHERE (n.parent_id IS NOT NULL AND (p.id IS NULL OR p.repertoire_id<>n.repertoire_id OR n.fen_before IS NOT p.fen)) " +
                "OR (n.parent_id IS NULL AND n.fen_before IS NOT NULL AND n.fen_before<>'')",null).use {
                assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0))
            }
            val select = "SELECT n.id,n.parent_id,n.path_id,n.uci,n.san,n.fen,p.fen,n.theory,n.line_alternative,n.kind,n.reason,n.comment,n.starting_comment,n.srs " +
                "FROM nodes n LEFT JOIN nodes p ON p.id=n.parent_id WHERE n.repertoire_id=? AND "
            fun rows(where: String,args: Array<String>) = db.rawQuery(select+where,args).use { cursor ->
                buildList { while(cursor.moveToNext()) add((0 until cursor.columnCount).map { if(cursor.isNull(it))null else cursor.getString(it) }) }.sortedBy { it[0]!!.toLong() }
            }
            db.rawQuery("SELECT DISTINCT repertoire_id,fen_before FROM nodes WHERE fen_before IS NOT NULL AND fen_before<>'' AND id%197=0",null).use { positions ->
                var count = 0
                while(positions.moveToNext()) {
                    val rep = positions.getString(0); val fen = positions.getString(1)
                    assertEquals(rows("n.parent_id IN (SELECT id FROM nodes WHERE repertoire_id=? AND fen=?)",arrayOf(rep,rep,fen)),
                        rows("n.fen_before=?",arrayOf(rep,fen)))
                    val book = RepertoirePositionBook(db,rep,JSONObject())
                    for(uci in listOf(null,"a2a3")) {
                        val (sql,args) = book.childQuery(fen,uci)
                        db.rawQuery("EXPLAIN QUERY PLAN " + sql,args).use { plan ->
                            val details = buildList { while(plan.moveToNext())add(plan.getString(3)) }.joinToString("\n")
                            assertTrue(details,details.contains("nodes_before (repertoire_id=? AND fen_before=?)"))
                        }
                    }
                    count++
                }
                assertTrue(count>100); println("Complete indexed/parent-join rows matched at $count corpus positions; all-edge invariant and indexed plan verified")
            }
        }
    }

    @Test fun measureCompleteNativeRequestsOnRealNavigationWorkloads() {
        val index = System.getenv("REPERTOIRE_TEST_INDEX")
        val workloads = System.getenv("REPERTOIRE_BENCH_CASES")
        val output = System.getenv("REPERTOIRE_BENCH_OUTPUT")
        assumeTrue(index!=null && workloads!=null && output!=null)
        val context = RuntimeEnvironment.getApplication()
        File(index!!).copyTo(File(context.filesDir,"mobile_repertoire.sqlite"),overwrite=true)
        val cases = JSONArray(File(workloads!!).readText())
        val store = RepertoireStore(context)
        val measurements = JSONArray()
        val baseline = System.getenv("REPERTOIRE_BENCH_REFERENCE")?.let { JSONObject(File(it).readText()).getJSONArray("measurements") }
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i); val raw = case.getJSONObject("request").toString()
            val samples = mutableListOf<Double>(); var response = ""
            repeat(7) {
                val start = System.nanoTime()
                response = store.lookup(JSONObject(raw)).toString()
                samples.add((System.nanoTime()-start)/1e6)
            }
            baseline?.let { assertEquals("Complete response: ${case.getString("name")}",canonical(JSONObject(it.getJSONObject(i).getString("response"))),canonical(JSONObject(response))) }
            val median = samples.drop(1).sorted()[3]
            val row = JSONObject().put("name",case.getString("name")).put("samples_ms",JSONArray(samples))
                .put("first_ms",samples.first()).put("median_ms",median).put("bytes",response.toByteArray().size).put("response",response)
            if(i<18) {
                val cold = (0..2).map {
                    val fresh = RepertoireStore(context); val start = System.nanoTime()
                    val actual = fresh.lookup(JSONObject(raw)).toString()
                    val time = (System.nanoTime()-start)/1e6
                    assertEquals(canonical(JSONObject(response)),canonical(JSONObject(actual)))
                    time
                }
                row.put("cold_samples_ms",JSONArray(cold)).put("cold_median_ms",cold.sorted()[1])
            }
            measurements.put(row)
            println("Lookup ${case.getString("name")}: first ${samples.first()} ms; warm $median ms; ${response.length} chars")
        }
        File(output!!).writeText(JSONObject().put("environment","Robolectric API 35 / native SQLite on host; not phone timings")
            .put("measurements",measurements).toString())
    }

    @Test fun realCorpusNavigationAfterAddExcludeUndoAndInstallKeepsExactFacts() {
        val index = System.getenv("REPERTOIRE_TEST_INDEX")
        val workloads = System.getenv("REPERTOIRE_BENCH_CASES")
        val output = System.getenv("REPERTOIRE_BENCH_OUTPUT")
        assumeTrue(index!=null && workloads!=null && output!=null)
        val context = RuntimeEnvironment.getApplication()
        File(index!!).copyTo(File(context.filesDir,"mobile_repertoire.sqlite"),overwrite=true)
        val cases = JSONArray(File(workloads!!).readText())
        fun request(name: String): JSONObject = (0 until cases.length()).map { cases.getJSONObject(it) }
            .single { it.getString("name")==name }.getJSONObject("request").let { JSONObject(it.toString()) }
        val original = request("english-later/6")
        val added = request("outside-later/6")
        val transposed = request("same-outside-different-history/6")
        assertEquals(added.getString("fen"),transposed.getString("fen"))
        val store = RepertoireStore(context); val phases = JSONArray()
        fun english(result: JSONObject): JSONObject = result.getJSONArray("results").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id")=="symmetrical_english" }
        }
        fun measure(name: String): List<JSONObject> {
            return listOf(original,added,transposed).mapIndexed { i,r ->
                val raw = r.toString(); val samples = mutableListOf<Double>(); var response = ""
                repeat(5) {
                    val start = System.nanoTime(); response = store.lookup(JSONObject(raw)).toString(); samples.add((System.nanoTime()-start)/1e6)
                }
                phases.put(JSONObject().put("name","$name/$i").put("samples_ms",JSONArray(samples)).put("response",response))
                JSONObject(response)
            }
        }
        val before = measure("before-edit")
        assertEquals(1,english(before[1]).getInt("add_count")); assertEquals(17,english(before[1]).getInt("deviation"))
        assertEquals(5,english(before[2]).getInt("add_count")); assertEquals(13,english(before[2]).getInt("deviation"))
        store.edit(JSONObject(added.toString()).put("id","symmetrical_english").put("kind","add"))
        val afterAdd = measure("after-add")
        assertTrue(english(afterAdd[1]).getBoolean("theory")); assertTrue(english(afterAdd[2]).getBoolean("theory"))
        assertEquals(0,english(afterAdd[2]).getInt("deviation"))
        store.undo(store.undoInfo()!!.getString("token"))
        val afterUndo = measure("after-add-undo")
        assertEquals(before.map(::canonical),afterUndo.map(::canonical))
        val exclusion = JSONObject(original.toString()).put("id","symmetrical_english").put("kind","analysis")
        val outgoing = english(before[0]).getJSONArray("moves").getJSONObject(0).getString("uci")
        exclusion.getJSONArray("history").put(outgoing); store.edit(exclusion)
        val afterExclude = measure("after-exclude")
        assertNotEquals(canonical(before[0]),canonical(afterExclude[0]))
        store.undo(store.undoInfo()!!.getString("token"))
        val afterExcludeUndo = measure("after-exclude-undo")
        assertEquals(before.map(::canonical),afterExcludeUndo.map(::canonical))
        val bytes = File(index).readBytes()
        store.install(bytes.inputStream(),RepertoireStore.hash(bytes))
        val afterInstall = measure("after-install")
        assertEquals(before.map(::canonical),afterInstall.map(::canonical))
        System.getenv("REPERTOIRE_BENCH_REFERENCE")?.let {
            val reference = JSONArray(File("$it.edits.json").readText())
            for(i in 0 until phases.length()) assertEquals("Edit phase $i",canonical(JSONObject(reference.getJSONObject(i).getString("response"))),canonical(JSONObject(phases.getJSONObject(i).getString("response"))))
        }
        File("$output.edits.json").writeText(phases.toString())
    }
}
