package com.instinctazero.android

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class StudyLibraryTest {
    private fun state(id: String) = JSONObject().put("v",1).put("boardId",id).put("studyId","study")
        .put("title","My study").put("chapterTitle",id).put("tree",org.json.JSONArray())
    @Test fun chaptersAreIndependentDurableAndListedWithoutTrees() {
        val dir=Files.createTempDirectory("study-library-test").toFile()
        val store=StudyLibrary(dir)
        assertTrue(store.save(state("first").toString()));assertTrue(store.save(state("second").toString()))
        val reopened=StudyLibrary(dir)
        assertEquals(2,reopened.list().length())
        assertFalse(reopened.list().getJSONObject(0).has("tree"))
        assertEquals("first",JSONObject(reopened.read("first")).getString("chapterTitle"))
        reopened.delete("second");assertEquals(1,reopened.list().length())
        assertEquals("first",JSONObject(reopened.read("first")).getString("boardId"))
    }
    @Test fun archiveAndInvalidWritesCannotOverwriteExistingChapter() {
        val store=StudyLibrary(Files.createTempDirectory("study-rejection-test").toFile())
        val original=state("first").toString();store.save(original)
        for(raw in listOf(state("first").put("gameId","archive1").toString(),state("../escape").toString(),"x".repeat(1048577))) {
            assertTrue(runCatching{store.save(raw)}.isFailure)
        }
        assertEquals(original,store.read("first"))
    }
    @Test fun interruptedAtomicSaveRecoversThePreviousChapter() {
        val directory=Files.createTempDirectory("study-recovery-test").toFile()
        val store=StudyLibrary(directory);val original=state("first").toString();store.save(original)
        assertTrue(java.io.File(directory,"first.json").renameTo(java.io.File(directory,"first.json.bak")))
        assertEquals(1,StudyLibrary(directory).list().length())
        assertEquals(original,store.read("first"))
    }
    @Test fun legacyStudyMigratesOnceAndAccountChangesDoNotEraseLocalBoards() {
        val activity=org.robolectric.Robolectric.buildActivity(MainActivity::class.java).get()
        val preferences=activity.getSharedPreferences("local_study_state",android.content.Context.MODE_PRIVATE)
        val legacy=JSONObject().put("v",1).put("gameId",JSONObject.NULL).put("initialFen","start")
            .put("title","Existing board").put("tree",org.json.JSONArray()).put("cursor",org.json.JSONArray())
        preferences.edit().putString("state_v1",legacy.toString()).commit()
        val bridge=NativeAnalysisBridge(activity)
        try {
            val migrated=JSONObject(bridge.getStudyState())
            assertEquals("legacy-analysis",migrated.getString("boardId"))
            assertEquals("Existing board",JSONObject(bridge.readBoard("legacy-analysis")).getString("title"))
            bridge.clearArchivedStudyContext()
            assertEquals(migrated.toString(),bridge.getStudyState())
            assertEquals(1,org.json.JSONArray(bridge.listBoards()).length())
        } finally {bridge.close()}
    }
}
