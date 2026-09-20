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
    @Test fun editorDraftPersistsWithoutReplacingEitherLegacyBoard() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("workspace",Context.MODE_PRIVATE)
        prefs.edit().putString("state_v1","original study").putString("position_state_v1","original scratch board").commit()
        val store=StudyWorkspaceStore(prefs)
        assertTrue(store.saveDraft(JSONObject().put("fen",fen).put("black",true).toString()))
        val reopened=StudyWorkspaceStore(prefs)
        assertEquals(fen,JSONObject(reopened.draft()).getString("fen"))
        assertTrue(JSONObject(reopened.draft()).getBoolean("black"))
        assertFalse(reopened.saveDraft("{\"fen\":\"not a FEN\"}"))
        assertEquals(fen,JSONObject(reopened.draft()).getString("fen"))
        assertEquals("original study",prefs.getString("state_v1",null))
        assertEquals("original scratch board",prefs.getString("position_state_v1",null))
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
