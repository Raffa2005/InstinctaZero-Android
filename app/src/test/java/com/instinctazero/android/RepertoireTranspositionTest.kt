package com.instinctazero.android

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Public synthetic comments + Rafael's move-order reproduction; no private PGN material. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class RepertoireTranspositionTest {
    private val fixture = JSONObject(javaClass.getResourceAsStream("/qga-transposition.json")!!.bufferedReader().readText())
    private lateinit var store: RepertoireStore
    private lateinit var original: ByteArray
    private fun request(name: String, ply: Int = 16): JSONObject {
        val line = fixture.getJSONObject(name)
        val history = JSONArray((0 until ply).map { line.getJSONArray("history").getString(it) })
        val entries = JSONArray((0 until ply).map { line.getJSONArray("entries").getJSONObject(it) })
        return JSONObject().put("root",line.getString("root")).put("history",history).put("entries",entries)
            .put("fen",if(ply==0)RepertoireStore.position(line.getString("root")) else entries.getJSONObject(ply-1).getString("fen"))
            .put("selected",JSONArray().put("qga"))
    }
    private fun lookup(name: String, ply: Int = 16) = store.lookup(request(name,ply)).getJSONArray("results").getJSONObject(0)
    private fun moves(result: JSONObject) = (0 until result.getJSONArray("moves").length()).map { result.getJSONArray("moves").getJSONObject(it) }
    private fun active(result: JSONObject) = moves(result).filter { it.getBoolean("theory") }.map { it.getString("uci") }.toSet()
    private fun adjust(name: String, uci: String, kind: String) {
        val request = request(name)
        request.getJSONArray("history").put(uci)
        store.edit(request.put("id","qga").put("kind",kind))
    }
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir,"qga.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT,value TEXT)")
            db.execSQL("INSERT INTO meta VALUES('schema_version','1')")
            db.execSQL("CREATE TABLE repertoires(id TEXT,name TEXT,side TEXT,pgn TEXT)")
            db.execSQL("INSERT INTO repertoires VALUES('qga','QGA fixture','black','fixture.pgn'),('other','Other','white','other.pgn')")
            db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,parent_id INTEGER,repertoire_id TEXT,path_id TEXT,uci TEXT,san TEXT,kind TEXT,theory INTEGER,line_alternative INTEGER,reason TEXT,comment TEXT,fen TEXT,starting_comment TEXT)")
            db.execSQL("CREATE INDEX nodes_fen ON nodes(repertoire_id,fen)")
            db.execSQL("CREATE INDEX nodes_parent ON nodes(parent_id)")
            db.execSQL("CREATE INDEX nodes_path ON nodes(repertoire_id,path_id)")
            var id = 0
            for ((name,info) in listOf("reported" to false,"rd1" to false,"e4" to false,"e4" to true)) {
                val line = fixture.getJSONObject(name); val root = line.getString("root")
                val history = mutableListOf<String>(); var parent: Int? = null
                for (ply in 0..line.getJSONArray("history").length()) {
                    val entry = if(ply>0)line.getJSONArray("entries").getJSONObject(ply-1) else null
                    if(ply>0)history.add(line.getJSONArray("history").getString(ply-1))
                    id++
                    val comment = if(ply==16 && name!="reported")"Keep the knight flexible."
                        else if(ply==17)"Continuation note for $name." else ""
                    val active = !info || ply<17
                    db.execSQL("INSERT INTO nodes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,parent,"qga",RepertoireStore.pathId(root,history),history.lastOrNull(),entry?.getString("san"),
                        if(active)"repertoire" else "analysis",if(active)1 else 0,0,"Fixture annotation",comment,
                        entry?.getString("fen") ?: RepertoireStore.position(root),if(ply==17)"Before the $name continuation." else ""))
                    parent = id
                }
            }
        }
        original = file.readBytes(); store = RepertoireStore(context)
        store.install(original.inputStream(),RepertoireStore.hash(original))
    }
    @Test fun terminalReferenceMergesBothMovesAndDeduplicatedPositionComments() {
        val reported = lookup("reported"); val canonical = lookup("canonical")
        assertTrue(reported.getBoolean("theory")); assertFalse(reported.getBoolean("end_of_line"))
        assertEquals(0,reported.getInt("deviation")); assertFalse(reported.getBoolean("can_add"))
        assertEquals(setOf("f1d1","e3e4"),active(reported))
        assertEquals(1,reported.getJSONArray("comments").length())
        assertEquals("Keep the knight flexible.",reported.getJSONArray("comments").getString(0))
        assertEquals(canonical.toString(),reported.toString())
        // A study starting directly from this FEN also gets the full book, with no history.
        val fromPosition = request("reported").put("root",reported.getString("fen")).put("history",JSONArray()).put("entries",JSONArray())
        assertEquals(active(reported),active(store.lookup(fromPosition).getJSONArray("results").getJSONObject(0)))
    }
    @Test fun anInactiveDuplicateDoesNotVetoAnActiveEdgeAndOpponentMovesAreNotAlternatives() {
        for (move in moves(lookup("reported"))) {
            assertTrue(move.getBoolean("theory")); assertFalse(move.getBoolean("own")); assertFalse(move.getBoolean("alternative"))
            assertEquals("repertoire",move.getString("kind"))
            assertTrue(move.getJSONObject("position").getBoolean("theory"))
            assertFalse(move.getJSONObject("position").getBoolean("end_of_line"))
        }
        for (name in listOf("rd1","e4")) {
            val next = lookup(name,17)
            assertEquals(setOf("b7b5"),active(next))
            assertTrue(next.getJSONArray("comments").toString().contains("Continuation note"))
        }
        assertThrows(IllegalArgumentException::class.java) { adjust("reported","e3e4","alternative") }
    }
    @Test fun arrivingAfterAnExcludedPathRejoinsAnIndependentlyCoveredPosition() {
        val before = request("reported",4)
        before.getJSONArray("history").put("g1f3")
        store.edit(before.put("id","qga").put("kind","analysis"))
        assertFalse(lookup("reported",5).getBoolean("theory"))
        assertTrue(lookup("reported").getBoolean("theory"))
        assertEquals(setOf("f1d1","e3e4"),active(lookup("reported")))
        assertEquals(0,lookup("reported").getInt("deviation"))
    }
    @Test fun positionEditsAndUndoWorkFromEitherMoveOrderWithoutTouchingTheSource() {
        adjust("reported","f1d1","analysis")
        assertEquals(setOf("e3e4"),active(lookup("reported")))
        assertEquals(setOf("e3e4"),active(lookup("canonical")))
        store = RepertoireStore(RuntimeEnvironment.getApplication())
        store.undo(store.undoInfo()!!.getString("token"))
        assertEquals(setOf("f1d1","e3e4"),active(lookup("reported")))
        val other = store.lookup(request("reported").put("selected",JSONArray().put("other"))).getJSONArray("results").getJSONObject(0)
        assertFalse(other.getBoolean("known")); assertTrue(active(other).isEmpty())
        assertArrayEquals(original,File(RuntimeEnvironment.getApplication().filesDir,"mobile_repertoire.sqlite").readBytes())
    }
    @Test fun extendingATranspositionAddsOnlyTheNewEdgesAndUndoRemovesThemEverywhere() {
        val line = request("addition",18)
        assertEquals(2,store.lookup(line).getJSONArray("results").getJSONObject(0).getInt("add_count"))
        store.edit(line.put("id","qga").put("kind","add"))
        assertEquals("Added 2 moves",store.undoInfo()!!.getString("label"))
        assertEquals(setOf("f1d1","e3e4","a2a3"),active(lookup("canonical")))
        assertTrue(lookup("addition",18).getBoolean("theory"))
        store.undo(store.undoInfo()!!.getString("token"))
        assertEquals(setOf("f1d1","e3e4"),active(lookup("canonical")))
        assertFalse(lookup("addition",18).getBoolean("theory"))
    }
    @Test fun legacyPathAdditionsAndTheirExistingUndoSurviveThePositionBookUpgrade() {
        val line = fixture.getJSONObject("addition"); val root = line.getString("root")
        val history = (0 until 18).map { line.getJSONArray("history").getString(it) }
        val local = JSONObject(); val changes = JSONArray()
        for (ply in 17..18) {
            val key = RepertoireStore.pathId(root,history.take(ply))
            val entry = line.getJSONArray("entries").getJSONObject(ply-1)
            local.put(key,JSONObject().put("added",true).put("kind","repertoire")
                .put("parent",RepertoireStore.pathId(root,history.take(ply-1)))
                .put("uci",history[ply-1]).put("san",entry.getString("san")).put("fen",entry.getString("fen")))
            changes.put(JSONObject().put("path",key).put("before",JSONObject.NULL))
        }
        // The v0.7.3/v0.7.4 journal hashes a canonical JSON object, before adding _undo.
        fun canonical(value: Any?): String = when(value) {
            null,JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
        val saved = JSONObject().put("qga",local)
        val journal = JSONObject().put("v",1).put("token","legacy-test").put("repertoire","qga").put("name","QGA fixture")
            .put("label","Added 2 moves").put("had_repertoire",false).put("changes",changes)
            .put("after_hash",RepertoireStore.hash(canonical(saved).toByteArray(Charsets.UTF_8)))
        val file = File(RuntimeEnvironment.getApplication().filesDir,"mobile_repertoire_edits.json")
        file.writeText(saved.put("_undo",journal).toString())
        store = RepertoireStore(RuntimeEnvironment.getApplication())
        assertEquals("legacy-test",store.undoInfo()!!.getString("token"))
        assertTrue(lookup("addition",18).getBoolean("theory"))
        assertTrue(active(lookup("canonical")).contains("a2a3"))
        assertEquals(saved.toString(),file.readText()) // Reading does not migrate/rewrite saved data.
        store.undo("legacy-test")
        assertFalse(lookup("addition",18).getBoolean("theory"))
        assertEquals(setOf("f1d1","e3e4"),active(lookup("canonical")))
        assertArrayEquals(original,File(RuntimeEnvironment.getApplication().filesDir,"mobile_repertoire.sqlite").readBytes())
    }
}
