package com.instinctazero.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the actual native views without opening a PC connection or using a physical device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePresentationTest {
    @Test
    fun nativeHomeAndRecycledGamesRemainReadableAtPhoneWidths() {
        for (width in listOf(360, 390, 412)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h780dp-mdpi")
            // Attaching the Activity context is enough for its view factories. Do not call
            // onCreate: this preview does not initialize credentials, WebView, or HTTP clients.
            val activity = Robolectric.buildActivity(MainActivity::class.java).get()
            activity.setTheme(R.style.Theme_InstinctaZero)
            val account = MainActivity::class.java.getDeclaredField("archiveAccount").apply { isAccessible = true }
            account.set(activity, "Rafael")
            val home = MainActivity::class.java.getDeclaredMethod("homeContent", Boolean::class.javaPrimitiveType, String::class.java)
                .apply { isAccessible = true }.invoke(activity, true, "Analysis PC connected") as View
            val homeScreen = screen(activity, home)
            draw(homeScreen, width, "home-$width")
            assertTrue(textViews(homeScreen).any { it.text.toString().startsWith("Analysis board") })
            assertTrue(textViews(homeScreen).all { it.right <= width && it.bottom <= 780 })

            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val rowClass = MainActivity::class.java.declaredClasses.single { it.simpleName == "GameRowView" }
            val constructor = rowClass.getDeclaredConstructor(MainActivity::class.java, Context::class.java).apply { isAccessible = true }
            val bind = rowClass.getDeclaredMethod("bind", JSONObject::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val outcomes = listOf("1-0", "0-1", "1/2-1/2")
            outcomes.forEachIndexed { index, result ->
                val row = constructor.newInstance(activity, activity) as View
                val game = fixture(result)
                bind.invoke(row, game, index)
                rows.addView(row)
                assertTrue(row.isClickable)
                assertTrue(row.contentDescription.contains("Rafael"))
                if (index == 0) {
                    // Recycler reuse must replace result and accessibility text, not keep a win.
                    bind.invoke(row, fixture("0-1"), index)
                    assertTrue(textViews(row).any { it.text.contains("Lost") })
                    bind.invoke(row, game, index)
                }
            }
            val navigation = MainActivity::class.java.getDeclaredField("navigation").apply { isAccessible = true }.get(activity) as ShellNavigation
            navigation.showGames()
            val gamesScreen = screen(activity, rows)
            draw(gamesScreen, width, "games-$width")
            val labels = textViews(rows)
            assertEquals(3, labels.count { it.text.toString() == "Rafael" })
            assertTrue(labels.any { it.text.contains("Won") })
            assertTrue(labels.any { it.text.contains("Lost") })
            assertTrue(labels.any { it.text.contains("Draw") })
            assertTrue(labels.filter { it.text.contains("Won") || it.text.contains("Lost") }.all { it.textSize >= 12 })
        }
    }

    private fun screen(activity: MainActivity, content: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xff191918.toInt())
        val header = MainActivity::class.java.getDeclaredMethod("nativeHeader").apply { isAccessible = true }.invoke(activity) as View
        addView(header)
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun draw(view: View, width: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, 780)
        val bitmap = Bitmap.createBitmap(width, 780, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/reports/phone-preview/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun fixture(result: String) = JSONObject()
        .put("id", "demo1234").put("result", result).put("status", "resign")
        .put("analyzable", true).put("rated", true).put("speed", "blitz").put("variant", "standard")
        .put("clock", JSONObject().put("initial", 180).put("increment", 2))
        .put("last_move_at_ms", 1788564000000L)
        .put("white", JSONObject().put("name", "Rafael").put("rating", 2134))
        .put("black", JSONObject().put("name", "QueensideExplorer").put("rating", 2091))
        .put("preview_fen", "r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 4 7")
}
