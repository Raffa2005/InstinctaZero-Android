package com.instinctazero.android

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Presentation only. Never passed to authentication, ownership, storage or export code. */
internal class AccountPrivacy(private val prefs: SharedPreferences) {
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    private var names = runCatching {
        val array=JSONArray(prefs.getString("known_names","[]"))
        (0 until array.length()).map { array.getString(it) }.takeLast(128)
    }.getOrDefault(emptyList())
    private var matcher: Regex? = pattern()
    private fun pattern() = names.takeIf { it.isNotEmpty() }?.let {
        Regex(it.sortedByDescending(String::length).joinToString("|",transform=Regex::escape),RegexOption.IGNORE_CASE)
    }
    @Synchronized fun remember(vararg identities: String) {
        val next=(names+identities.filter { it.matches(Regex("[A-Za-z0-9_-]{2,40}")) }.map { it.lowercase(Locale.ROOT) }).distinct().takeLast(128)
        if(next!=names){names=next;matcher=pattern();prefs.edit().putString("known_names",JSONArray(names).toString()).apply()}
    }
    fun setEnabled(value: Boolean): Boolean = prefs.edit().putBoolean("enabled",value).commit()
    @Synchronized fun config() = JSONObject().put("enabled",enabled).put("names",JSONArray(names))
    @Synchronized fun text(raw: String): String {
        if(!enabled)return raw
        val metadata=raw.replace(Regex("\\[(\\w+)\\s+\"(?:[^\"\\\\]|\\\\.)*\"\\]")) {
            if(it.groupValues[1].matches(Regex("FEN|SetUp|Result|Variant",RegexOption.IGNORE_CASE)))it.value
            else "[${it.groupValues[1]} \"Hidden\"]"
        }
        val withoutLinks=metadata.replace(Regex("https?://\\S+|(?:www\\.)?lichess\\.org/\\S+",RegexOption.IGNORE_CASE),"[link hidden]")
        return matcher?.replace(withoutLinks,"Player") ?: withoutLinks
    }
    fun account(raw: String) = if(enabled)"Player" else raw
    fun player(raw: String, owner: String) = if(!enabled)raw else if(raw.equals(owner,true))"Player" else "Opponent"
    fun message(raw: String, fallback: String): String = if(!enabled)raw else when(raw) {
        "Loading game…", "Refreshing accounts…", "Refreshing games…" -> raw
        else -> if(raw.startsWith("Switching to "))"Switching accounts…" else fallback
    }
}

internal object PrivacyModeView {
    fun build(context: Context, privacy: AccountPrivacy, changed: (Boolean) -> Unit): View {
        val ui=WorkspaceMenu(context); val dp=context.resources.displayMetrics.density
        return ui.column().apply {
            setPadding((4*dp).toInt(),(5*dp).toInt(),(4*dp).toInt(),(10*dp).toInt())
            addView(Switch(context).apply {
                text="Privacy mode"; textSize=16f; setTextColor(Color.WHITE); minimumHeight=(48*dp).toInt()
                gravity=Gravity.CENTER_VERTICAL; isChecked=privacy.enabled
                contentDescription="Privacy mode. On hides account names; off shows real identities."
                thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(0xffc4af75.toInt(),0xffa5a5a0.toInt()))
                setOnCheckedChangeListener { _, value -> changed(value) }
            },LinearLayout.LayoutParams(-1,-2))
            addView(ui.text(if(privacy.enabled)"On · Player alias; names and ratings hidden" else "Off · real account names are visible",12f,true))
            addView(ui.text("Screen privacy only. Public Lichess games and original PGNs remain identifiable.",12f,true).apply { setPadding(0,(4*dp).toInt(),0,0) })
        }
    }
}
