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

    @Test fun endOfLineIncludesInformationalTailsAndExcludesUnknownHistories() {
        val terminal = listOf("e2e4","e7e5","g1f3","b8c6")
        SQLiteDatabase.openDatabase(File(context.filesDir,"mobile_repertoire.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO nodes VALUES(9,5,'white',?,'f1b5','Bb5','analysis',0,0,'Information','A tail',?)",arrayOf(RepertoireStore.pathId(root,terminal+"f1b5"),RepertoireStore.position(root)))
        }
        assertTrue(result(terminal).getBoolean("end_of_line"))
        assertEquals(1,result(terminal).getJSONArray("moves").length())
        assertTrue(result(terminal.dropLast(1)).getJSONArray("moves").getJSONObject(0).getBoolean("end_of_line"))
        assertFalse(result(listOf("d2d4")).getBoolean("end_of_line"))
        edit(terminal.take(3),"analysis")
        assertTrue(result(terminal.take(2)).getBoolean("end_of_line"))
        assertFalse(result(terminal.take(3)).getBoolean("end_of_line"))
    }

    @Test fun quickAddExtendsTerminalCoverageAndPersistsOnlyToChosenRepertoire() {
        val terminal = listOf("e2e4","e7e5","g1f3","b8c6")
        val extended = terminal + listOf("f1b5","a7a6")
        assertTrue(result(terminal).getBoolean("end_of_line"))
        assertTrue(result(extended).getBoolean("can_add")); assertEquals(2,result(extended).getInt("add_count"))
        val request = request(extended).put("id","white").put("kind","add")
            .put("entries",JSONArray(extended.map { JSONObject().put("san",it).put("fen",root) }))
        store.edit(request)
        store = RepertoireStore(context)
        assertTrue(result(extended).getBoolean("theory")); assertTrue(result(extended).getBoolean("end_of_line"))
        assertFalse(result(terminal).getBoolean("end_of_line")); assertFalse(result(extended).getBoolean("can_add"))
        assertThrows(IllegalArgumentException::class.java) { store.edit(request) }
        assertFalse(store.lookup(request(extended,listOf("black"))).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
        assertArrayEquals(bytes,File(context.filesDir,"mobile_repertoire.sqlite").readBytes())
    }

    @Test fun addEligibilityAndWritesBothRejectExcludedLeavesAndSourceInformation() {
        edit(listOf("e2e4"),"analysis")
        for (moves in listOf(listOf("e2e4"),listOf("e2e4","c7c5"),listOf("e2e4","f7f6","g1f3"))) {
            assertFalse(result(moves).getBoolean("can_add"))
            assertThrows(IllegalArgumentException::class.java) { store.edit(request(moves).put("id","white").put("kind","add").put("entries",JSONArray(moves.map { JSONObject().put("san",it).put("fen",root) }))) }
        }
        edit(listOf("e2e4"),"reset")
        assertFalse(result(listOf("e2e4","f7f6")).getBoolean("can_add"))
        assertTrue(result(listOf("e2e4","c7c5")).getBoolean("can_add"))
        assertEquals(1,result(listOf("e2e4","c7c5")).getInt("add_count"))
    }

    @Test fun failedMultiMoveAdditionRollsBackAllNewMoves() {
        val moves = listOf("e2e4","c7c5","g1f3")
        val entries = JSONArray().put(JSONObject().put("san","e4").put("fen",root))
            .put(JSONObject().put("san","c5").put("fen",root)).put(JSONObject())
        assertThrows(org.json.JSONException::class.java) { store.edit(request(moves).put("id","white").put("kind","add").put("entries",entries)) }
        assertFalse(result(moves.take(2)).getBoolean("theory"))
        assertEquals(2,result(moves).getInt("add_count"))
        assertArrayEquals(bytes,File(context.filesDir,"mobile_repertoire.sqlite").readBytes())
    }

    private fun addLine(moves: List<String>, rep: String = "white") = store.edit(request(moves).put("id",rep).put("kind","add")
        .put("entries",JSONArray(moves.map { JSONObject().put("san",it).put("fen",root) })))
    private fun editsWithoutUndo(): Map<String, Map<String, String>> {
        val saved = JSONObject(File(context.filesDir,"mobile_repertoire_edits.json").readText()).also { it.remove("_undo") }
        return saved.keys().asSequence().associateWith { rep ->
            val paths = saved.getJSONObject(rep)
            paths.keys().asSequence().associateWith { paths.getJSONObject(it).toString() }
        }
    }
    private fun undoToken() = store.undoInfo()!!.getString("token")

    @Test fun undoAddedLineSurvivesRestartAndCorpusUpdateAndPreservesExistingPrefixes() {
        addLine(listOf("e2e4","c7c5"))
        edit(listOf("e2e4"),"alternative")
        edit(listOf("e2e4"),"analysis","black")
        store.saveSettings("{\"analysis\":[\"white\",\"black\"],\"_bookMarker\":true}")
        val settings = store.settings(); val before = editsWithoutUndo()
        val line = listOf("e2e4","c7c5","g1f3","b8c6")
        addLine(line)
        assertEquals("Added 2 moves",store.undoInfo()!!.getString("label"))
        val token = undoToken()
        store = RepertoireStore(context)
        store.install(bytes.inputStream(),RepertoireStore.hash(bytes))
        assertEquals(token,store.catalog().getJSONObject("undo").getString("token"))
        store.undo(token)
        assertTrue(result(line.take(2)).getBoolean("theory")); assertTrue(result(line.take(2)).getBoolean("alternative"))
        assertFalse(result(line).getBoolean("theory")); assertEquals(2,result(line).getInt("add_count"))
        assertEquals(before,editsWithoutUndo()); assertEquals(settings,store.settings())
        assertNull(store.undoInfo())
        store = RepertoireStore(context); assertNull(store.undoInfo())
        assertArrayEquals(bytes,File(context.filesDir,"mobile_repertoire.sqlite").readBytes())
    }

    @Test fun undoExclusionRestoresTheExactPriorAlternativeLabel() {
        edit(listOf("e2e4"),"alternative")
        val before = editsWithoutUndo()
        edit(listOf("e2e4"),"analysis")
        assertFalse(result(listOf("e2e4","e7e5")).getBoolean("theory"))
        store.undo(undoToken())
        assertTrue(result(listOf("e2e4","e7e5")).getBoolean("theory"))
        assertTrue(result(listOf("e2e4","e7e5")).getBoolean("alternative"))
        assertEquals(before,editsWithoutUndo())
    }

    @Test fun undoRemovalRestoresLocalAdditionAndItsDescendants() {
        val moves = listOf("e2e4","c7c5","g1f3")
        addLine(moves); val before = editsWithoutUndo()
        edit(moves.take(2),"reset")
        assertEquals("Removed local addition",store.undoInfo()!!.getString("label"))
        assertFalse(result(moves).getBoolean("theory"))
        store.undo(undoToken())
        assertEquals(before,editsWithoutUndo()); assertTrue(result(moves).getBoolean("theory"))
        assertTrue(result(moves).getBoolean("end_of_line"))
    }

    @Test fun undoOptionalAndRestoredLabelsDoesNotTouchSourceData() {
        assertNull(store.undoInfo())
        assertThrows(IllegalStateException::class.java) { store.undo("none") }
        edit(listOf("e2e4"),"alternative"); store.undo(undoToken())
        assertFalse(result(listOf("e2e4")).getBoolean("alternative"))
        edit(listOf("e2e4"),"analysis"); edit(listOf("e2e4"),"reset")
        assertTrue(result(listOf("e2e4")).getBoolean("theory"))
        store.undo(undoToken()); assertFalse(result(listOf("e2e4")).getBoolean("theory"))
        assertArrayEquals(bytes,File(context.filesDir,"mobile_repertoire.sqlite").readBytes())
    }

    @Test fun failedAndNoOpEditsKeepTheLastMeaningfulUndo() {
        edit(listOf("e2e4"),"analysis"); val token = undoToken()
        val before = File(context.filesDir,"mobile_repertoire_edits.json").readBytes()
        edit(listOf("e2e4"),"analysis")
        edit(listOf("e2e4","e7e5"),"reset")
        assertThrows(IllegalArgumentException::class.java) { addLine(listOf("e2e4","c7c5")) }
        assertEquals(token,undoToken()); assertArrayEquals(before,File(context.filesDir,"mobile_repertoire_edits.json").readBytes())
        store.undo(token); assertTrue(result(listOf("e2e4")).getBoolean("theory"))
    }

    @Test fun staleOrRepeatedUndoCannotUndoADifferentRepertoiresNewerChange() {
        edit(listOf("e2e4"),"analysis"); val stale = undoToken()
        edit(listOf("e2e4"),"analysis","black"); val latest = undoToken()
        val before = editsWithoutUndo()
        assertThrows(IllegalArgumentException::class.java) { store.undo(stale) }
        assertEquals(before,editsWithoutUndo()); assertEquals("black",store.undoInfo()!!.getString("repertoire"))
        store.undo(latest)
        assertFalse(result(listOf("e2e4")).getBoolean("theory"))
        assertTrue(store.lookup(request(listOf("e2e4"),listOf("black"))).getJSONArray("results").getJSONObject(0).getBoolean("theory"))
        assertThrows(IllegalStateException::class.java) { store.undo(latest) }
    }

    @Test fun failedAtomicWritesPreserveBothTheEditsAndTheirUndo() {
        edit(listOf("e2e4"),"analysis"); val token = undoToken()
        val before = File(context.filesDir,"mobile_repertoire_edits.json").readBytes()
        val blocker = File(context.filesDir,"mobile_repertoire_edits.json.new")
        assertTrue(blocker.mkdir())
        try {
            assertThrows(java.io.IOException::class.java) { store.undo(token) }
            assertThrows(java.io.IOException::class.java) { edit(listOf("e2e4"),"analysis","black") }
            assertEquals(token,undoToken()); assertFalse(result(listOf("e2e4")).getBoolean("theory"))
            assertArrayEquals(before,File(context.filesDir,"mobile_repertoire_edits.json").readBytes())
        } finally { assertTrue(blocker.delete()) }
        store = RepertoireStore(context); store.undo(token)
        assertTrue(result(listOf("e2e4")).getBoolean("theory"))
    }

    @Test fun oldEditsAndInvalidJournalsRemainUsableWithoutInventingAnUndo() {
        edit(listOf("e2e4"),"analysis")
        val saved = File(context.filesDir,"mobile_repertoire_edits.json")
        val original = saved.readText()
        val corrupt = JSONObject(original); corrupt.getJSONObject("_undo").remove("changes"); saved.writeText(corrupt.toString())
        store = RepertoireStore(context); assertNull(store.undoInfo()); assertFalse(result(listOf("e2e4")).getBoolean("theory"))
        val stale = JSONObject(original)
        stale.put("black",JSONObject().put(RepertoireStore.pathId(root,listOf("e2e4")),JSONObject().put("kind","analysis")))
        saved.writeText(stale.toString()); store = RepertoireStore(context)
        assertNull(store.undoInfo()); assertFalse(result(listOf("e2e4")).getBoolean("theory"))
        stale.remove("_undo"); saved.writeText(stale.toString()); store = RepertoireStore(context)
        assertNull(store.undoInfo())
        edit(listOf("e2e4"),"reset"); store.undo(undoToken())
        assertFalse(result(listOf("e2e4")).getBoolean("theory"))
    }
}
