package com.instinctazero.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared native menu language: grouped destinations, generous touch targets, quiet icons. */
internal class WorkspaceMenu(private val context: Context) {
    private val font = Typeface.createFromAsset(context.assets,"analysis/fonts/fontawesome-webfont.ttf")
    private fun dp(n: Int) = (n*context.resources.displayMetrics.density+.5f).toInt()
    fun text(label: String, size: Float = 16f, muted: Boolean = false) = TextView(context).apply {
        text=label; textSize=size; setTextColor(if(muted)0xffaaa9a2.toInt() else 0xffeeeeea.toInt())
    }
    fun column() = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun section(label: String) = text(label.uppercase(),11f,true).apply {
        letterSpacing=.12f; setPadding(dp(4),dp(20),0,dp(9))
    }
    fun row(icon: String, title: String, subtitle: String? = null, action: () -> Unit): View = LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(14),dp(12),dp(12),dp(12)); minimumHeight=dp(if(subtitle==null)52 else 76)
        background=RippleDrawable(ColorStateList.valueOf(0x33c4af75),GradientDrawable().apply {
            setColor(0xff32332f.toInt()); cornerRadius=dp(10).toFloat()
        },null)
        isClickable=true; isFocusable=true; contentDescription=listOfNotNull(title,subtitle).joinToString(". ")
        addView(text(icon,20f).apply { typeface=font; gravity=Gravity.CENTER; setTextColor(0xffc4af75.toInt()); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(32),dp(40)).apply { marginEnd=dp(12) })
        addView(column().apply {
            addView(text(title,17f).apply { typeface=Typeface.DEFAULT_BOLD })
            if(subtitle!=null) addView(text(subtitle,13f,true).apply { setPadding(0,dp(3),0,0) })
        },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        addView(text("\uf105",16f,true).apply { typeface=font; gravity=Gravity.END; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(20),dp(30)))
        setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(5) }
    }
    fun scroll(content: View) = ScrollView(context).apply { isFillViewport=true; addView(content) }
    fun home(paired: Boolean, connection: String, analysis: () -> Unit, games: () -> Unit,
        library: () -> Unit, repertoire: () -> Unit, profile: () -> Unit, editor: () -> Unit): View = scroll(column().apply {
        setPadding(dp(16),dp(2),dp(16),dp(18))
        addView(section("Analysis"))
        addView(row("\uf201","Analysis board","Continue where you left off",analysis))
        addView(row("\uf044","Board editor","Set up a position to analyse",editor))
        addView(row("\uf009","Games",if(paired)"Your completed games" else "Connect your PC to browse games",games))
        addView(section("Repertoires"))
        addView(row("\uf02d","Repertoire library","Create and manage your openings",library))
        addView(row("\uf279","Repertoire board","Explore your selected openings",repertoire))
        addView(section("Connection"))
        addView(row("\uf108","Account / PC",connection,profile))
    })
}
