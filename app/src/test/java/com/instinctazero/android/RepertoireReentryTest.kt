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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RepertoireReentryTest {
    private val start=RepertoireStore.START_POSITION
    private val played=listOf("d2d4","d7d5","g1f3")
    private lateinit var source: File
    private lateinit var store: RepertoireStore
    private fun request(moves: List<String> = played): JSONObject {
        var fen=start;val entries=JSONArray()
        for(uci in moves) { val move=RepertoireLegalMoves.from(fen).single { it.uci==uci };entries.put(JSONObject().put("san",RepertoireLegalMoves.san(fen,uci)).put("fen",move.fen));fen=move.fen }
        return JSONObject().put("root",start).put("fen",fen).put("history",JSONArray(moves)).put("entries",entries).put("selected",JSONArray().put("book"))
    }
    private fun result(moves: List<String> = played)=store.lookup(request(moves)).getJSONArray("results").getJSONObject(0)
    private fun moves(result: JSONObject)=result.getJSONArray("moves").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun adjust(uci: String,kind: String)=store.edit(request().put("id","book").put("kind",kind).also { it.getJSONArray("history").put(uci) })
    private fun install() { source.inputStream().use { store.install(it,RepertoireStore.hash(source.readBytes())) } }
    private fun line(db: SQLiteDatabase,history: List<String>, offset: Int, inactive: Boolean=false) {
        var fen=start;var parent: Int?=null
        for(ply in 0..history.size) {
            val uci=history.getOrNull(ply-1);val before=fen
            val san=uci?.let { RepertoireLegalMoves.san(fen,it) }
            if(uci!=null)fen=RepertoireLegalMoves.from(fen).single { it.uci==uci }.fen
            val info=inactive && ply==history.size
            db.execSQL("INSERT INTO nodes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(offset+ply,parent,"book",RepertoireStore.pathId(start,history.take(ply)),uci,san,if(info)"refutation" else "repertoire",if(info)0 else 1,0,"Source label",if(ply==4)"Destination source note." else "",fen,"",if(ply==0)null else before,0))
            parent=offset+ply
        }
    }
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication();source=File(context.cacheDir,"reentry-source.sqlite")
        SQLiteDatabase.openOrCreateDatabase(source,null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT,value TEXT)");db.execSQL("INSERT INTO meta VALUES('schema_version','1')")
            db.execSQL("CREATE TABLE repertoires(id TEXT,name TEXT,side TEXT,pgn TEXT)");db.execSQL("INSERT INTO repertoires VALUES('book','Fixture','black','fixture.pgn')")
            db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,parent_id INTEGER,repertoire_id TEXT,path_id TEXT,uci TEXT,san TEXT,kind TEXT,theory INTEGER,line_alternative INTEGER,reason TEXT,comment TEXT,fen TEXT,starting_comment TEXT,fen_before TEXT,srs INTEGER)")
            db.execSQL("CREATE INDEX nodes_fen ON nodes(repertoire_id,fen)");db.execSQL("CREATE INDEX nodes_before ON nodes(repertoire_id,fen_before)");db.execSQL("CREATE INDEX nodes_path ON nodes(repertoire_id,path_id)")
            line(db,played,1)
            line(db,listOf("g1f3","g8f6","d2d4","d7d5","c2c4"),100)
        }
        store=RepertoireStore(context);install()
    }
    @Test fun missingLegalBridgeAppearsAtTerminalAndNonterminalAndLoadsDestination() {
        val result=result();val bridge=moves(result).single { it.getString("uci")=="g8f6" }
        assertTrue(bridge.getBoolean("theory"));assertEquals("Nf6",bridge.getString("san"))
        assertFalse(result.getBoolean("end_of_line"));assertFalse(store.markers(request()).getJSONArray("results").getJSONObject(0).getBoolean("end_of_line"))
        assertEquals("Destination source note.",bridge.getJSONObject("position").getJSONArray("comments").getString(0))
        assertTrue(moves(result(played+"g8f6")).any { it.getString("uci")=="c2c4" })
        SQLiteDatabase.openDatabase(source.path,null,0).use { line(it,played+"e7e6",200) };install()
        assertTrue(moves(result()).map { it.getString("uci") }.containsAll(listOf("g8f6","e7e6")))
        assertEquals(1,moves(result()).count { it.getString("uci")=="g8f6" })
        // Repertoire-return sees the missing association too; actual notation is untouched.
        val next=request(played+"e7e6").put("intersections",true)
        assertEquals(3,store.lookup(next).getJSONArray("results").getJSONObject(0).getInt("intersection"))
        System.getenv("REPERTOIRE_REENTRY_PREVIEW")?.let { path ->
            store.edit(request(played+"g8f6").put("id","book").put("kind","comment").put("comment","My private note."))
            val fixtures=JSONArray()
            for(history in listOf(played,played+"g8f6",played+"g8f6"+"c2c4"))fixtures.put(JSONObject().put("request",request(history)).put("result",result(history)))
            File(path).writeText(JSONObject().put("positions",fixtures).toString())
        }
    }
    @Test fun explicitInformationalEdgeAndInactiveDestinationAreNeverPromoted() {
        SQLiteDatabase.openDatabase(source.path,null,0).use { db -> line(db,played+"g8f6",200,true) };install()
        val bridge=moves(result()).single { it.getString("uci")=="g8f6" }
        assertFalse(bridge.getBoolean("theory"));assertEquals("refutation",bridge.getString("kind"))
        assertTrue(result().getBoolean("end_of_line"))
        SQLiteDatabase.openDatabase(source.path,null,0).use { db -> db.execSQL("DELETE FROM nodes WHERE id>=200");db.execSQL("UPDATE nodes SET theory=0,kind='analysis' WHERE id>=104") };install()
        assertFalse(moves(result()).any { it.getString("uci")=="g8f6" })
    }
    @Test fun derivedDeletionExclusionUndoAndRestartAreStableAndDoNotReappearOnRefresh() {
        adjust("g8f6","delete");val backup=store.backupSnapshot()
        assertFalse(moves(result()).any { it.getString("uci")=="g8f6" });assertTrue(result().getBoolean("end_of_line"))
        assertTrue(store.markers(request()).getJSONArray("results").getJSONObject(0).getBoolean("end_of_line"))
        store=RepertoireStore(RuntimeEnvironment.getApplication());install()
        assertFalse(moves(result()).any { it.getString("uci")=="g8f6" })
        store.undo(store.undoInfo()!!.getString("token"));assertTrue(moves(result()).any { it.getString("uci")=="g8f6" })
        store.restoreBackup(backup);assertFalse(moves(result()).any { it.getString("uci")=="g8f6" })
        adjust("g8f6","restore_move");adjust("g8f6","analysis")
        assertFalse(moves(result()).single { it.getString("uci")=="g8f6" }.getBoolean("theory"))
        // Manually reaching its independently covered destination can still extend
        // that position. This must NOT restore the excluded incoming association.
        store.edit(request(played+listOf("g8f6","c2c3")).put("id","book").put("kind","add"))
        assertFalse(moves(result()).single { it.getString("uci")=="g8f6" }.getBoolean("theory"))
    }
    @Test fun sourceRefreshWithRenumberedNodesRetainsNotesLabelsAdditionsAndUndo() {
        SQLiteDatabase.openDatabase(source.path,null,0).use { line(it,played+"e7e6",200) };install()
        store.edit(request(played+"a7a6").put("id","book").put("kind","add"))
        adjust("g8f6","main");adjust("e7e6","delete")
        store.edit(request(played+"g8f6").put("id","book").put("kind","comment").put("comment","My private note."))
        val before=store.backupSnapshot();val undo=store.undoInfo()!!.getString("token")
        SQLiteDatabase.openDatabase(source.path,null,0).use { db ->
            db.execSQL("UPDATE nodes SET id=id+10000,parent_id=parent_id+10000")
            db.execSQL("UPDATE nodes SET comment=comment || ' Verified source clarification.' WHERE comment<>''")
        };install();store=RepertoireStore(RuntimeEnvironment.getApplication())
        assertEquals(UnifiedRepertoireDatabase.canonical(before.getJSONObject("edits")),UnifiedRepertoireDatabase.canonical(store.backupSnapshot().getJSONObject("edits")))
        val now=result();assertTrue(moves(now).any { it.getString("uci")=="a7a6" });assertFalse(moves(now).any { it.getString("uci")=="e7e6" })
        assertEquals("main",moves(now).single { it.getString("uci")=="g8f6" }.getString("recommendation"))
        val destination=result(played+"g8f6");assertEquals("My private note.",destination.getJSONArray("comments").getString(0))
        assertTrue(destination.getJSONArray("source_comments").toString().contains("Verified source clarification."))
        store.undo(undo);assertTrue(result(played+"g8f6").getJSONArray("comments").toString().contains("Verified source clarification."))
        store.restoreBackup(before);assertEquals("My private note.",result(played+"g8f6").getJSONArray("comments").getString(0))
    }
    @Test fun legalPositionIdentityPreservesCastlingLegalEpAndPromotionButIgnoresCounters() {
        assertEquals(RepertoireLegalMoves.from(start),RepertoireLegalMoves.from("$start 45 91"))
        val pinned="k3r3/8/8/3pP3/8/8/8/4K3 w - d6"
        assertFalse(RepertoireLegalMoves.from(pinned).any { it.uci=="e5d6" })
        val legal="k7/8/8/3pP3/8/8/8/4K3 w - d6"
        assertTrue(RepertoireLegalMoves.from(legal).any { it.uci=="e5d6" })
        assertEquals(4,RepertoireLegalMoves.from("7k/P7/8/8/8/8/8/7K w - -").count { it.uci.startsWith("a7a8") })
        assertTrue(RepertoireLegalMoves.from("r3k2r/8/8/8/8/8/8/R3K2R w KQkq -").any { it.uci=="e1g1" })
        assertFalse(RepertoireLegalMoves.from("r3k2r/8/8/8/8/8/8/R3K2R w - -").any { it.uci=="e1g1" })
    }
}
