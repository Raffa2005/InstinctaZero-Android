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

/** Optional local integration test. Private corpus and reference outputs never enter the repo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class RepertoireCorpusTest {
    @Test fun nativePositionBookMatchesIndependentPositionUnionsOnARealCorpus() {
        val index = System.getenv("REPERTOIRE_TEST_INDEX")
        val reference = System.getenv("REPERTOIRE_TEST_CASES")
        assumeTrue(index != null && reference != null)
        val store = RepertoireStore(RuntimeEnvironment.getApplication())
        val bytes = File(index!!).readBytes()
        store.install(bytes.inputStream(),RepertoireStore.hash(bytes))
        val cases = JSONArray(File(reference!!).readText())
        val times = mutableListOf<Long>()
        val previews = JSONObject()
        for (i in 0 until cases.length()) {
            val test = cases.getJSONObject(i)
            val started = System.nanoTime()
            val actual = store.lookup(JSONObject(test.toString()).put("selected",JSONArray().put(test.getString("rep"))))
                .getJSONArray("results").getJSONObject(0)
            times.add((System.nanoTime()-started)/1_000_000)
            assertEquals("Theory case $i ${test.getString("rep")}",test.getBoolean("theory"),actual.getBoolean("theory"))
            val moves = actual.getJSONArray("moves")
            val active = (0 until moves.length()).map { moves.getJSONObject(it) }.filter { it.getBoolean("theory") }.map { it.getString("uci") }.sorted()
            val expected = test.getJSONArray("moves")
            assertEquals("Continuations case $i", (0 until expected.length()).map { expected.getString(it) }, active)
            for (field in listOf("comments","starting_comments")) {
                val notes = actual.getJSONArray(field); val referenceNotes = test.getJSONArray(field)
                assertEquals("$field case $i",(0 until referenceNotes.length()).map { referenceNotes.getString(it) }.sorted(),
                    (0 until notes.length()).map { notes.getString(it) }.sorted())
            }
            if (test.getString("rep")=="qga") previews.put(test.getString("fen"),actual)
        }
        System.getenv("REPERTOIRE_PREVIEW_OUTPUT")?.let { File(it).writeText(previews.toString()) }
        println("Private corpus: ${cases.length()} reference matches; host median ${times.sorted()[times.size/2]} ms, max ${times.max()} ms (not a physical phone benchmark)")
    }
}
