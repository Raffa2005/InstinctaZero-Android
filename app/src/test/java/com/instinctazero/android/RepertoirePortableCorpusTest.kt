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

/** Cross-language test using only the generated public fixture, never a real book. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class RepertoirePortableCorpusTest {
    @Test fun generatedPythonIndexWorksOfflineWithReentryAndPersistentEdits() {
        val path = System.getenv("REPERTOIRE_PORTABLE_INDEX")
        assumeTrue("Generate the public fixture and set REPERTOIRE_PORTABLE_INDEX", path != null)
        val source = File(path!!)
        assertTrue(source.isFile)
        val context = RuntimeEnvironment.getApplication()
        var store = RepertoireStore(context)
        val bytes = source.readBytes()
        source.inputStream().use { store.install(it, RepertoireStore.hash(bytes)) }
        assertTrue(store.catalog().getJSONArray("repertoires").length() >= 1)
        fun request(history: List<String>): JSONObject {
            var fen = RepertoireStore.START_POSITION
            val entries = JSONArray()
            for (uci in history) {
                val move = RepertoireLegalMoves.from(fen).single { it.uci == uci }
                entries.put(JSONObject().put("san", RepertoireLegalMoves.san(fen, uci)).put("fen", move.fen))
                fen = move.fen
            }
            return JSONObject().put("root", RepertoireStore.START_POSITION).put("fen", fen)
                .put("history", JSONArray(history)).put("entries", entries)
                .put("selected", JSONArray().put("synthetic_1"))
        }
        val history = listOf("d2d4", "d7d5", "g1f3")
        fun moves(): List<String> {
            val result = store.lookup(request(history)).getJSONArray("results").getJSONObject(0)
            val array = result.getJSONArray("moves")
            return (0 until array.length()).map { array.getJSONObject(it).getString("uci") }
        }
        assertTrue("Missing association must be discovered by position", "g8f6" in moves())
        // Move adjustments describe an outgoing move from the current position.
        store.edit(request(history).put("id", "synthetic_1").put("kind", "delete").also {
            it.getJSONArray("history").put("g8f6")
        })
        assertFalse("g8f6" in moves())
        val backup = store.backupSnapshot()
        store = RepertoireStore(context)
        source.inputStream().use { store.install(it, RepertoireStore.hash(bytes)) }
        assertFalse("Refresh/restart must preserve deletion", "g8f6" in moves())
        store.undo(store.undoInfo()!!.getString("token"))
        assertTrue("g8f6" in moves())
        store.restoreBackup(backup)
        assertFalse("g8f6" in moves())
        assertEquals("Read-only fixture must stay intact", RepertoireStore.hash(bytes), RepertoireStore.hash(source.readBytes()))
    }
}
