package com.instinctazero.android

import android.graphics.Bitmap
import android.graphics.Canvas
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
            val canvas=Canvas(bitmap); canvas.drawColor(0xff2b2b2b.toInt()); view.draw(canvas)
            File(folder,"$name-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun actualNativeMenuHasGroupedIconDestinationsAndWorkingTouchTargets() {
        val context=RuntimeEnvironment.getApplication(); val menu=WorkspaceMenu(context)
        for((width,height) in listOf(360 to 584,390 to 724,412 to 788)) {
            val clicked=mutableListOf<String>()
            val home=menu.home(false,"Not paired · tap to connect",{clicked.add("analysis")},{clicked.add("games")},
                {clicked.add("library")},{clicked.add("repertoire")},{clicked.add("profile")})
            snapshot(home,"home",width,height)
            val rows=views(home).filter { it.isClickable }
            assertEquals(5,rows.size)
            for(row in rows) { assertTrue(row.height>=48);row.performClick() }
            assertEquals(listOf("analysis","games","library","repertoire","profile"),clicked)
            val text=views(home).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(text.containsAll(listOf("ANALYSIS","REPERTOIRES","CONNECTION")))
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
}
