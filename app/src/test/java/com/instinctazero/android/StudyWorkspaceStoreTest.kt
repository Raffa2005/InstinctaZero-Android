package com.instinctazero.android

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class StudyWorkspaceStoreTest {
    private val fen="4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17"
    @Test fun editedAnalysisKeepsTheOriginalGameAndDraftAcrossRestart() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("workspace",Context.MODE_PRIVATE)
        val store=StudyWorkspaceStore(prefs)
        val original="""{"v":1,"gameId":"GameOld1","cursor":["e2e4"],"tree":[{"u":"e2e4","c":[]}]}"""
        assertTrue(store.save(original));val source=store.source()
        assertTrue(store.saveDraft(JSONObject().put("fen",fen).put("black",true).toString()))
        assertEquals(source,store.current())
        val edited=JSONObject().put("v",1).put("editedPosition",true).put("gameId",JSONObject.NULL).put("initialFen",fen).put("cursor",org.json.JSONArray())
        assertTrue(store.save(edited.toString()));assertEquals(source,store.source())
        val recreated=StudyWorkspaceStore(prefs)
        assertEquals(fen,JSONObject(recreated.current()).getString("initialFen"));assertEquals(fen,JSONObject(recreated.draft()).getString("fen"))
        assertTrue(recreated.save(recreated.source()));assertEquals(source,recreated.current())
        assertTrue(prefs.contains("position_state_v1"));assertEquals(source,recreated.source())
    }
    @Test fun invalidWritesDoNotReplaceEitherSavedBoard() {
        val store=StudyWorkspaceStore(RuntimeEnvironment.getApplication().getSharedPreferences("invalid-workspace",Context.MODE_PRIVATE))
        assertTrue(store.save("""{"v":1,"gameId":"GameOld1"}"""));val source=store.source()
        assertFalse(store.save("""{"v":1,"editedPosition":true,"gameId":"GameOld1"}"""))
        assertFalse(store.save("x".repeat(262145)));assertFalse(store.save("{}"))
        assertEquals(source,store.current());assertEquals(source,store.source())
        assertTrue(store.saveDraft(JSONObject().put("fen",fen).toString()))
        assertFalse(store.saveDraft("{\"fen\":\"not a FEN\"}"));assertEquals(fen,JSONObject(store.draft()).getString("fen"))
    }
    @Test fun fenTransportRetainsEpCastlingAndCountersButRejectsUnboundedOrMalformedValues() {
        assertEquals(fen,PositionFen.checked(fen))
        val castles="r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 23 99"
        assertEquals(castles,PositionFen.checked(castles))
        for(raw in listOf("8/8/8/8/8/8/8/8 w - - 0 0","8/8/8/8/8/8/8/8 w - - 0 10000","8/8/8/8/8/8/8/7 w - - 0 1","8/8/8/8/8/8/8/8 x - - 0 1",fen+" evil")) {
            assertTrue(raw,runCatching { PositionFen.checked(raw) }.isFailure)
        }
    }
}
