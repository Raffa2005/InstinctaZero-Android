package com.instinctazero.android

import android.content.Context
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class RollbackStudyPreservationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private fun state(title: String, fen: String = start) = JSONObject().put("v",1)
        .put("title",title).put("initialFen",fen).put("gameId",JSONObject.NULL)
        .put("tree",org.json.JSONArray()).toString()

    @Test fun richerStateAndEveryLibraryFileSurviveSimpleBoardWrites() {
        val source=context.getSharedPreferences("local_study_state",Context.MODE_PRIVATE)
        val rich=JSONObject(state("Imported repertoire")).put("repertoireColor","w")
            .put("comments","Do not lose these").toString()
        source.edit().putString("state_v1",rich).commit()
        val directory=File(context.filesDir,"study_library").apply{mkdirs()}
        val originals=mapOf("legacy-analysis.json" to state("Original simple board"),
            "chapter.json" to rich,"chapter.json.bak" to "backup bytes", "chapter.json.new" to "pending bytes")
        originals.forEach{(name,raw)->File(directory,name).writeText(raw)}
        val simple=RollbackStudyPreservation.open(context)
        assertEquals(originals["legacy-analysis.json"],simple.getString("state_v1",null))
        simple.edit().putString("state_v1",state("Edited simple board")).commit()
        assertEquals(state("Edited simple board"),RollbackStudyPreservation.open(context).getString("state_v1",null))
        assertEquals(rich,source.getString("state_v1",null))
        originals.forEach{(name,raw)->assertEquals(raw,File(directory,name).readText())}
    }
    @Test fun largeActiveStateIsCopiedButItsOriginalCannotBeTruncatedByTheOldEditor() {
        val source=context.getSharedPreferences("local_study_state",Context.MODE_PRIVATE)
        val original=JSONObject(state("Large chapter")).put("extra","x".repeat(300000)).toString()
        source.edit().putString("state_v1",original).commit()
        val simple=RollbackStudyPreservation.open(context)
        assertEquals(original,simple.getString("state_v1",null))
        simple.edit().clear().putBoolean("seeded",true).commit()
        assertEquals(original,source.getString("state_v1",null))
    }
    @Test fun unsupportedCustomRootRemainsRecoverableWithoutSendingWrongAnalysis() {
        val source=context.getSharedPreferences("local_study_state",Context.MODE_PRIVATE)
        val custom=state("Endgame","8/8/8/8/8/2k5/8/K7 b - - 0 1")
        source.edit().putString("state_v1",custom).commit()
        assertNull(RollbackStudyPreservation.open(context).getString("state_v1",null))
        assertEquals(custom,source.getString("state_v1",null))
    }
}
