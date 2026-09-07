package com.instinctazero.android

import android.content.Context
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
@Config(sdk=[35], manifest=Config.NONE)
class RepertoireStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val root = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private lateinit var store: RepertoireStore
    private lateinit var bytes: ByteArray
    @Before fun setup() {
        val fixture = File(context.cacheDir, "fixture.sqlite")
        SQLiteDatabase.openOrCreateDatabase(fixture, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT,value TEXT)")
            db.execSQL("INSERT INTO meta VALUES('schema_version','1')")
            db.execSQL("CREATE TABLE repertoires(id TEXT,name TEXT,side TEXT,pgn TEXT)")
            db.execSQL("INSERT INTO repertoires VALUES('white','White test','white','white.pgn'),('black','Black test','black','black.pgn')")
            db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,parent_id INTEGER,repertoire_id TEXT,path_id TEXT,uci TEXT,san TEXT,kind TEXT,theory INTEGER,line_alternative INTEGER,reason TEXT,comment TEXT,fen TEXT)")
            for ((rep, offset) in listOf("white" to 0, "black" to 100)) {
                fun node(id: Int, parent: Int?, moves: List<String>, kind: String = "repertoire", theory: Int = 1, alt: Int = 0) {
                    db.execSQL("INSERT INTO nodes VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(id+offset,parent?.plus(offset),rep,RepertoireStore.pathId(root,moves),moves.lastOrNull(),moves.lastOrNull(),kind,theory,alt,"Test reason","Comments stay visible",RepertoireStore.position(root)))
                }
                node(1,null,emptyList()); node(2,1,listOf("e2e4")); node(3,2,listOf("e2e4","e7e5"))
                node(4,3,listOf("e2e4","e7e5","g1f3"),"alternative",1,1)
                node(5,4,listOf("e2e4","e7e5","g1f3","b8c6"),"repertoire",1,1)
                node(6,2,listOf("e2e4","f7f6"),"refutation",0)
                node(7,6,listOf("e2e4","f7f6","d2d4"),"analysis",0)
                node(8,1,listOf("e2e4"),"model_game",0)
            }
        }
        bytes = fixture.readBytes(); store = RepertoireStore(context)
        store.install(bytes.inputStream(), RepertoireStore.hash(bytes))
    }
    private fun request(moves: List<String>, selected: List<String> = listOf("white")) = JSONObject()
        .put("root",root).put("fen",root).put("history",JSONArray(moves)).put("selected",JSONArray(selected))
    private fun result(moves: List<String>) = store.lookup(request(moves)).getJSONArray("results").getJSONObject(0)
    private fun edit(moves: List<String>, kind: String, rep: String = "white") = store.edit(request(moves).put("id",rep).put("kind",kind))

    @Test fun activeAndInformationalOccurrencesCoexistAndTerminalTheoryIsValid() {
        assertTrue(result(listOf("e2e4")).getBoolean("theory"))
        val terminal = result(listOf("e2e4","e7e5","g1f3","b8c6"))
        assertTrue(terminal.getBoolean("theory")); assertEquals(0,terminal.getJSONArray("moves").length())
        assertTrue(terminal.getBoolean("alternative")); assertEquals("repertoire",terminal.getString("kind"))
    }
    @Test fun excludedHistoryCannotReactivateViaPositionMatch() {
        val excluded = result(listOf("e2e4","f7f6","d2d4"))
        assertFalse(excluded.getBoolean("theory")); assertEquals(2,excluded.getInt("deviation"))
        val unknown = result(listOf("d2d4"))
        assertTrue(unknown.getInt("candidates") > 0); assertFalse(unknown.getBoolean("theory"))
        assertEquals(1,unknown.getInt("deviation"))
    }
    @Test fun editsAreSeparateAndPersistWithCombinedAccess() {
        edit(listOf("e2e4"),"analysis")
        val both = store.lookup(request(listOf("e2e4","e7e5"),listOf("white","black"))).getJSONArray("results")
        assertFalse(both.getJSONObject(0).getBoolean("theory")); assertTrue(both.getJSONObject(1).getBoolean("theory"))
        store = RepertoireStore(context)
        assertFalse(result(listOf("e2e4")).getBoolean("theory"))
        edit(listOf("e2e4"),"reset"); assertTrue(result(listOf("e2e4")).getBoolean("theory"))
    }
    @Test fun onlyOwnSideCanIntroduceAlternatives() {
        edit(listOf("e2e4"),"alternative")
        assertTrue(result(listOf("e2e4","e7e5")).getBoolean("alternative"))
        assertEquals("repertoire",result(listOf("e2e4","e7e5")).getString("kind"))
        assertThrows(IllegalArgumentException::class.java) { edit(listOf("e2e4","e7e5"),"alternative") }
        edit(listOf("e2e4","e7e5"),"alternative","black")
    }
    @Test fun addingAndRemovingLocalLinesDoesNotChangeSourceOrCrossExcludedBridges() {
        val moves = listOf("e2e4","c7c5","g1f3")
        val entries = JSONArray(moves.map { JSONObject().put("san",it).put("fen",root) })
        store.edit(request(moves).put("id","white").put("kind","add").put("entries",entries))
        assertTrue(result(moves).getBoolean("theory"))
        edit(moves.take(2),"analysis"); assertFalse(result(moves).getBoolean("theory"))
        edit(moves.take(2),"reset"); assertFalse(result(moves).getBoolean("theory"))
        val excluded = listOf("e2e4","f7f6","g1f3")
        assertThrows(IllegalArgumentException::class.java) { store.edit(request(excluded).put("id","white").put("kind","add").put("entries",entries)) }
        assertArrayEquals(bytes,File(context.filesDir,"mobile_repertoire.sqlite").readBytes())
    }
    @Test fun failedDownloadsRetainDatabaseAndUpdatesRetainLocalEdits() {
        edit(listOf("e2e4"),"analysis")
        assertThrows(IllegalArgumentException::class.java) { store.install("broken".byteInputStream(),RepertoireStore.hash(bytes)) }
        assertArrayEquals(bytes,File(context.filesDir,"mobile_repertoire.sqlite").readBytes())
        store.install(bytes.inputStream(),RepertoireStore.hash(bytes))
        assertFalse(result(listOf("e2e4")).getBoolean("theory"))
    }
    @Test fun onlyFixedRepertoireRoutesAreAllowed() {
        val origin = BuildConfig.LEELA_GATEWAY_ORIGIN
        assertTrue(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/repertoires/index"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/repertoires/index?file=private"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/repertoires/../../file"))
    }
    @Test fun slowNetworkDownloadDoesNotBlockOfflineQueries() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val delayed = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown(); check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                return super.read(buffer,offset,length)
            }
        }
        val download = worker.submit { store.install(delayed,RepertoireStore.hash(bytes)) }
        try {
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(result(listOf("e2e4")).getBoolean("theory"))
        } finally { release.countDown(); download.get(5,java.util.concurrent.TimeUnit.SECONDS); worker.shutdownNow() }
    }

    @Test fun allDistinctSourceCommentsSurviveForCurrentMoveAndContinuations() {
        val longComment = "A full annotation with <markup> & variations.\n".repeat(110)
        SQLiteDatabase.openDatabase(File(context.filesDir,"mobile_repertoire.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE nodes SET comment=? WHERE id=8", arrayOf(longComment))
        }
        val played = result(listOf("e2e4")).getJSONArray("comments")
        assertEquals(2,played.length()); assertEquals(longComment,played.getString(1))
        val continuation = result(emptyList()).getJSONArray("moves").getJSONObject(0).getJSONArray("comments")
        assertEquals(played.toString(),continuation.toString())
        assertEquals(1,result(listOf("e2e4","e7e5")).getJSONArray("comments").length())
    }

    @Test fun transpositionMarkersRespectSourceExclusionsAndPositionIdentity() {
        val target = "rnbqkb1r/pppppppp/5n2/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq -"
        SQLiteDatabase.openDatabase(File(context.filesDir,"mobile_repertoire.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE nodes SET fen=? WHERE id=3",arrayOf(target))
        }
        fun match(fen: String = target) = store.lookup(request(listOf("g1f3","g8f6")).put("fen",fen)).getJSONArray("results").getJSONObject(0)
        assertTrue(match().getBoolean("position_match")); assertFalse(match().getBoolean("theory"))
        assertEquals(1,match().getInt("deviation"))
        assertFalse(match(target.replace("w KQkq", "b KQkq")).getBoolean("position_match"))
        assertFalse(match(target.replace("KQkq", "KQ")).getBoolean("position_match"))
        edit(listOf("e2e4"),"analysis")
        assertFalse(match().getBoolean("position_match"))
        edit(listOf("e2e4"),"reset")
        assertTrue(match().getBoolean("position_match"))
        assertEquals(0,store.lookup(request(listOf("g1f3"),emptyList()).put("fen",target)).getJSONArray("results").length())
    }

    @Test fun localTranspositionsDisappearWhenTheirParentIsRemovedOrExcluded() {
        val target = "rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq -"
        val moves = listOf("e2e4","c7c5","g1f3")
        fun add() = store.edit(request(moves).put("id","white").put("kind","add").put("entries",JSONArray(moves.mapIndexed { index, move -> JSONObject().put("san",move).put("fen",if(index==2)target else root) })))
        fun match() = store.lookup(request(listOf("g1f3","c7c5","e2e4")).put("fen",target)).getJSONArray("results").getJSONObject(0).getBoolean("position_match")
        add(); assertTrue(match())
        edit(moves.take(2),"analysis"); assertFalse(match())
        edit(moves.take(2),"reset"); assertFalse(match())
        add(); assertTrue(match())
        assertArrayEquals(bytes,File(context.filesDir,"mobile_repertoire.sqlite").readBytes())
    }
}
