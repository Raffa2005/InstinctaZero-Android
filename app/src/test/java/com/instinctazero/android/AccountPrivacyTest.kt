package com.instinctazero.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** No credentials, networking, emulator or USB device: real native UI with a fake PC bridge. */
@Implements(value=NativeAnalysisBridge::class,isInAndroidSdk=false)
class PrivacyBridgeShadow {
    @Implementation fun getConnectionState()=state.toString()
    @Implementation fun isPaired()=state.optBoolean("paired")
    @Implementation fun cachedArchive(): JSONObject?=null
    @Implementation fun refreshSession(): String?="session"
    @Implementation fun refreshArchive()="archive"
    @Implementation fun cancelAll(reason: String) { }
    @Implementation fun close() { }
    @Implementation fun engineBackendLabel()="CPU"
    @Implementation fun selectAccount(username: String): String? { selected=username;return "select" }
    @Implementation fun loadArchivedGame(id: String): String { loaded=id;return "game" }
    companion object { var state=JSONObject();var selected="";var loaded="" }
}

@Implements(value=androidx.webkit.WebViewFeature::class,isInAndroidSdk=false)
class PrivacyWebViewFeatureShadow {
    companion object { @JvmStatic @Implementation fun isFeatureSupported(feature: String)=false }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="mdpi",shadows=[PrivacyBridgeShadow::class,PrivacyWebViewFeatureShadow::class],instrumentedPackages=["com.instinctazero.android","androidx.webkit"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountPrivacyTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun views(view: View): List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun text(view: View)=views(view).joinToString("\n") { (it as? TextView)?.text.toString()+" "+it.contentDescription.toString() }
    private fun root(activity: MainActivity)=activity.findViewById<View>(android.R.id.content)
    private fun call(activity: MainActivity,name: String) { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity) }
    private fun field(target: Any,name: String): Any?=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun snapshot(view: View,name: String,width: Int=390,height: Int=724) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
        System.getenv("NATIVE_MENU_PREVIEW")?.let { path ->
            val folder=File(path).apply { mkdirs() };val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).let { it.drawColor(0xff2b2b2b.toInt());view.draw(it) }
            File(folder,"$name-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    private fun privateText(view: View) { assertFalse(text(view),Regex("SecretOwner|SecondAccount|RivalName|2101|1987|PrivateDevice",RegexOption.IGNORE_CASE).containsMatchIn(text(view))) }
    @Before fun setup() {
        context.getSharedPreferences("display_privacy",Context.MODE_PRIVATE).edit().clear().commit()
        PrivacyBridgeShadow.state=JSONObject("""{"paired":true,"accountUsername":"SecretOwner","deviceName":"PrivateDevice","availableAccounts":[{"username":"SecretOwner"},{"username":"SecondAccount"}]}""")
        PrivacyBridgeShadow.loaded="";PrivacyBridgeShadow.selected=""
    }
    @Test fun policyPersistsAndRedactsOldAccountsWithoutTouchingStoredGames() {
        val prefs=context.getSharedPreferences("display_privacy",Context.MODE_PRIVATE);val p=AccountPrivacy(prefs)
        val saved=context.getSharedPreferences("privacy_test_game",Context.MODE_PRIVATE);val raw="[White \"SecretOwner\"] 1. e4 *"
        saved.edit().putString("pgn",raw).commit()
        assertFalse(p.enabled);p.remember("SecretOwner","SecondAccount");assertTrue(p.setEnabled(true))
        assertEquals("Player",p.account("SecretOwner"));assertEquals("Opponent",p.player("RivalName","SecretOwner"));assertEquals("Player",p.player("secretowner","SecretOwner"))
        val reopened=AccountPrivacy(prefs);assertTrue(reopened.enabled)
        assertEquals("Player_black.pgn [link hidden]",reopened.text("SecretOwner_black.pgn https://lichess.org/@/SecondAccount"))
        assertEquals("Retry",reopened.message("UnknownSecret http://lichess.org/game","Retry"))
        assertEquals("Switching accounts…",reopened.message("Switching to SecondAccount…","Retry"))
        assertEquals(raw,saved.getString("pgn",null));assertTrue(reopened.setEnabled(false));assertEquals("SecretOwner",reopened.account("SecretOwner"))
        for(i in 0..300)reopened.remember("Account$i");assertEquals(128,reopened.config().getJSONArray("names").length())
    }
    @Test fun nativeHomeProfileGamesRecyclingAndRecreationUseAliasesButKeepRealOwnership() {
        AccountPrivacy(context.getSharedPreferences("display_privacy",Context.MODE_PRIVATE)).setEnabled(true)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        for(width in listOf(360,390,412)){snapshot(root(activity),"privacy-home",width);privateText(root(activity))}
        call(activity,"showProfileScreen");snapshot(root(activity),"privacy-profile");privateText(root(activity))
        views(root(activity)).filterIsInstance<TextView>().first { it.text.toString()=="Account 2" }.performClick()
        assertEquals("SecondAccount",PrivacyBridgeShadow.selected);privateText(root(activity))
        activity.onBridgeConnectionState(JSONObject(PrivacyBridgeShadow.state.toString()).put("error","SecretOwner failed at https://lichess.org/@/SecretOwner"));privateText(root(activity))
        val games=JSONArray()
        for(black in listOf(false,true))games.put(JSONObject().put("id",if(black)"Game0002" else "Game0001").put("white",JSONObject().put("name",if(black)"RivalName" else "SecretOwner").put("rating",2101))
            .put("black",JSONObject().put("name",if(black)"SecretOwner" else "RivalName").put("rating",1987)).put("analyzable",true).put("result",if(black)"0-1" else "1-0").put("status","resign").put("variant","standard").put("speed","blitz").put("preview_fen","rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))
        val payload=JSONObject().put("account","SecretOwner").put("games",games).put("total",2);val original=payload.toString()
        activity.onArchivePayload(payload,null);call(activity,"showGamesScreen");snapshot(root(activity),"privacy-games");privateText(root(activity))
        val list=views(root(activity)).filterIsInstance<ListView>().single();val adapter=list.adapter
        val first=adapter.getView(0,null,list);assertTrue(text(first).contains("Player  ⚔  Opponent"));assertFalse(field(views(first).filterIsInstance<GameThumbnailView>().single(),"blackAtBottom") as Boolean)
        val recycled=adapter.getView(1,first,list);assertSame(first,recycled);privateText(recycled);assertTrue(text(recycled).contains("Opponent  ⚔  Player"));assertTrue(field(views(recycled).filterIsInstance<GameThumbnailView>().single(),"blackAtBottom") as Boolean)
        assertTrue(text(recycled).contains("Opponent resigned. You won."))
        recycled.performClick();assertEquals("Game0002",PrivacyBridgeShadow.loaded);assertEquals(original,payload.toString())
        activity.onArchivePayload(null,"SecretOwner failed https://lichess.org/@/SecretOwner");privateText(root(activity))
        call(activity,"showProfileScreen");views(root(activity)).filterIsInstance<Switch>().single().isChecked=false
        assertFalse(activity.privacy.enabled);assertTrue(text(root(activity)).contains("SecretOwner"))
        views(root(activity)).filterIsInstance<Switch>().single().isChecked=true;privateText(root(activity));controller.recreate();assertTrue(controller.get().privacy.enabled);privateText(root(controller.get()));controller.pause().stop().destroy()
    }
    @Test fun libraryOriginalNameNeedsExplicitRevealAndIsNeverSavedAsAnAlias() {
        val privacy=AccountPrivacy(context.getSharedPreferences("display_privacy",Context.MODE_PRIVATE));privacy.remember("SecretOwner");privacy.setEnabled(true)
        val state=RepertoireLibraryState().apply { editing=true;id="local1";name="SecretOwner English" }
        var view: View?=null;var saved: JSONObject?=null
        fun render(){view=RepertoireLibraryView.build(context,state,{render()},{saved=it},{},privacy)}
        render();privateText(view!!);assertTrue(text(view!!).contains("reveals identity"));assertFalse(views(view!!).any { it is android.widget.EditText })
        views(view!!).first { it.contentDescription?.startsWith("Show original name")==true }.performClick()
        assertEquals("SecretOwner English",views(view!!).filterIsInstance<android.widget.EditText>().single().text.toString())
        views(view!!).first { it.contentDescription=="Save name" }.performClick();assertEquals("SecretOwner English",saved!!.getString("name"))
    }
}
