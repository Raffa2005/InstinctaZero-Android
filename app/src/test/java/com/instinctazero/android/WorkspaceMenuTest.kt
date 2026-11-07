package com.instinctazero.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceMenuTest {
    private fun views(view: View): List<View> = listOf(view) + if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun snapshot(view: View,name: String,width: Int,height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        System.getenv("NATIVE_MENU_PREVIEW")?.let { path ->
            val folder=File(path).apply { mkdirs() };val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap); canvas.drawColor(AppPalette.BACKGROUND); view.draw(canvas)
            File(folder,"$name-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun photoStyleMenuHasExactlySevenOrderedDestinationsAndWorkingTouchTargets() {
        val context=RuntimeEnvironment.getApplication(); val menu=WorkspaceMenu(context)
        for((width,height) in listOf(320 to 520,360 to 584,390 to 724,412 to 788)) {
            val clicked=mutableListOf<String>()
            val home=menu.home({clicked.add("analysis")},{clicked.add("editor")},{clicked.add("library")},
                {clicked.add("pgn")},{clicked.add("games")},{clicked.add("drill")},{clicked.add("profile")})
            snapshot(home,"home",width,height)
            val rows=views(home).filter { it.isClickable }
            assertEquals(7,rows.size)
            for((i,row) in rows.withIndex()) {
                assertTrue(row.height>=48);row.performClick()
                val fill=(row.background as RippleDrawable).getDrawable(0) as GradientDrawable
                assertEquals(if(i==0)AppPalette.CONTROL else android.graphics.Color.TRANSPARENT,fill.color!!.defaultColor)
            }
            assertEquals(listOf("analysis","editor","library","pgn","games","drill","profile"),clicked)
            val text=views(home).filterIsInstance<TextView>().map { it.text.toString() }
            assertEquals(listOf("Analysis","Board Editor","Repertoires","PGN Library","Lichess Games","Drill","Account/PC"),text)
            assertEquals(1,views(home).count { it.tag=="menu-knight" })
            assertEquals(1,views(home).count { it.tag=="menu-divider" })
            assertTrue(rows.zipWithNext().all { (a,b) -> a.bottom<=b.top })
        }
    }
    @Test fun largeTextScrollsWithoutClippingLabelsOrLosingTheLastDestination() {
        val app=RuntimeEnvironment.getApplication()
        val context=app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale=1.8f })
        var profile=false
        val home=WorkspaceMenu(context).home({},{},{},{},{},{},{profile=true}) as android.widget.ScrollView
        snapshot(home,"home-large-text",320,520)
        val rows=views(home).filter { it.isClickable }
        for(row in rows) {
            assertTrue(row.height>=48)
            val label=views(row).filterIsInstance<TextView>().single()
            assertTrue(label.layout.height<=label.height-label.paddingTop-label.paddingBottom)
            assertTrue(label.width>0)
        }
        home.scrollTo(0,home.getChildAt(0).height)
        val last=rows.last()
        assertTrue(last.bottom-home.scrollY<=home.height)
        assertTrue(last.top-home.scrollY>=0)
        last.performClick();assertTrue(profile)
        snapshot(home,"home-large-text-bottom",320,520)
    }
    @Test fun unimplementedDestinationsAreHonestPlaceholdersWithWorkingBack() {
        val menu=WorkspaceMenu(RuntimeEnvironment.getApplication())
        for(title in listOf("PGN Library","Drill")) {
            var back=false
            val page=menu.placeholder(title){back=true}
            snapshot(page,"placeholder-${title.replace(' ','-')}",360,584)
            val text=views(page).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(text.contains(title));assertTrue(text.contains("Not available yet"))
            views(page).filter { it.isClickable }.single().performClick()
            assertTrue(back)
        }
    }
    @Test fun nativeLibraryCreatesNamesChoosesColourAndOpensTheRightBook() {
        val context=RuntimeEnvironment.getApplication();val state=RepertoireLibraryState()
        state.catalog=JSONObject().put("repertoires",JSONArray().put(JSONObject().put("id","english").put("name","Symmetrical English").put("side","black")))
        var page: View?=null;var saved: JSONObject?=null;var opened: String?=null
        fun render() { page=RepertoireLibraryView.build(context,state,{ },{saved=it},{opened=it}) }
        render();snapshot(page!!,"library",360,584)
        views(page!!).first { it.contentDescription?.startsWith("New repertoire")==true }.performClick()
        assertTrue(state.editing);render()
        views(page!!).filterIsInstance<EditText>().single().setText("My English")
        views(page!!).first { it.contentDescription=="Black" }.performClick();render()
        snapshot(page!!,"create",360,584)
        views(page!!).first { it.contentDescription=="Create repertoire" }.performClick()
        assertEquals("My English",saved!!.getString("name"));assertEquals("black",saved!!.getString("side"))
        state.editing=false;render()
        views(page!!).first { it.contentDescription?.startsWith("Symmetrical English")==true }.performClick()
        assertEquals("english",opened)
    }
    @Test fun backupHistoryHasClearPrivateScopeAndRequiresAnExplicitRestoreConfirmation() {
        val context=RuntimeEnvironment.getApplication();val state=RepertoireLibraryState();val actions=mutableListOf<String>()
        state.backupsOpen=true;state.backup=JSONObject().put("saved_ms",1700000000000L).put("versions",JSONArray().put(JSONObject().put("id","a".repeat(64)).put("saved_ms",1700000000000L)))
        fun page()=RepertoireLibraryView.build(context,state,{},{},{},backupAction={ action,id -> actions.add("$action:$id") })
        val initial=page();snapshot(initial,"backups",390,724)
        val texts=views(initial).filterIsInstance<TextView>().joinToString(" ") { it.text }
        assertTrue(texts.contains("original PGNs are not changed"));assertFalse(texts.contains("a".repeat(64)))
        views(initial).first { it.contentDescription?.endsWith("From another paired phone")==true }.performClick()
        assertTrue(actions.isEmpty());assertEquals("a".repeat(64),state.restoreVersion)
        val confirmation=page();snapshot(confirmation,"restore-backup",360,584)
        views(confirmation).first { it.contentDescription=="Restore repertoire edits" }.performClick()
        assertEquals(listOf("restore:"+"a".repeat(64)),actions)
        state.backup.put("busy",true)
        assertFalse(views(page()).first { it.contentDescription=="Restore repertoire edits" }.isEnabled)
    }
}
