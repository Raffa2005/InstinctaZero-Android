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
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView

/** Shared native menu language: grouped destinations, generous touch targets, quiet icons. */
internal class WorkspaceMenu(private val context: Context) {
    private val font = Typeface.createFromAsset(context.assets,"analysis/fonts/fontawesome-webfont.ttf")
    private fun dp(n: Int) = (n*context.resources.displayMetrics.density+.5f).toInt()
    fun text(label: String, size: Float = 16f, muted: Boolean = false) = TextView(context).apply {
        text=label; textSize=size; setTextColor(if(muted)AppPalette.MUTED else AppPalette.TEXT)
    }
    fun column() = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun section(label: String) = text(label.uppercase(),11f,true).apply {
        letterSpacing=.12f; setPadding(dp(4),dp(20),0,dp(9))
    }
    fun row(icon: String, title: String, subtitle: String? = null, action: () -> Unit): View = LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(14),dp(12),dp(12),dp(12)); minimumHeight=dp(if(subtitle==null)52 else 76)
        background=RippleDrawable(ColorStateList.valueOf(AppPalette.RIPPLE),GradientDrawable().apply {
            setColor(AppPalette.SURFACE); cornerRadius=dp(10).toFloat()
        },null)
        isClickable=true; isFocusable=true; contentDescription=listOfNotNull(title,subtitle).joinToString(". ")
        addView(text(icon,20f).apply { typeface=font; gravity=Gravity.CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(32),dp(40)).apply { marginEnd=dp(12) })
        addView(column().apply {
            addView(text(title,17f).apply { typeface=Typeface.DEFAULT_BOLD })
            if(subtitle!=null) addView(text(subtitle,13f,true).apply { setPadding(0,dp(3),0,0) })
        },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        addView(text("\uf105",16f,true).apply { typeface=font; gravity=Gravity.END; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(20),dp(30)))
        setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(5) }
    }
    fun scroll(content: View) = ScrollView(context).apply { isFillViewport=true; addView(content) }
    fun home(analysis: () -> Unit, editor: () -> Unit, library: () -> Unit,
        pgnLibrary: () -> Unit, games: () -> Unit, drill: () -> Unit, profile: () -> Unit): View {
        val content = column()
        fun space(height: Int, weight: Float = 0f) = content.addView(View(context),
            LinearLayout.LayoutParams(1, dp(height), weight))
        space(8, .8f)
        content.addView(ImageView(context).apply {
            setImageDrawable(MenuGlyphDrawable(MenuGlyph.KNIGHT))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag="menu-knight"
        }, LinearLayout.LayoutParams(dp(48), dp(56)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        space(16, .4f)
        val rows = mutableListOf<View>()
        fun destination(glyph: MenuGlyph, label: String, action: () -> Unit) {
            val primary = glyph == MenuGlyph.ANALYSIS
            val shape = GradientDrawable().apply { setColor(if(primary) AppPalette.CONTROL else android.graphics.Color.TRANSPARENT); cornerRadius=dp(16).toFloat() }
            val mask = GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius=dp(16).toFloat() }
            val row = LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                minimumHeight=dp(48); setPadding(dp(22),dp(10),dp(16),dp(10))
                background=RippleDrawable(ColorStateList.valueOf(AppPalette.RIPPLE),shape,mask)
                contentDescription=label; isClickable=true; isFocusable=true
                accessibilityDelegate=object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host,info)
                        info.className=android.widget.Button::class.java.name
                    }
                }
                addView(ImageView(context).apply {
                    setImageDrawable(MenuGlyphDrawable(glyph)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(24) })
                addView(text(label,18f).apply {
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                setOnClickListener { action() }
            }
            rows.add(row)
            content.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        destination(MenuGlyph.ANALYSIS,"Analysis",analysis)
        destination(MenuGlyph.EDITOR,"Board Editor",editor)
        destination(MenuGlyph.REPERTOIRES,"Repertoires",library)
        destination(MenuGlyph.PGN_LIBRARY,"PGN Library",pgnLibrary)
        destination(MenuGlyph.GAMES,"Lichess Games",games)
        destination(MenuGlyph.DRILL,"Drill",drill)
        content.addView(View(context).apply { setBackgroundColor(AppPalette.BORDER); tag="menu-divider" },
            LinearLayout.LayoutParams(-1,dp(1)).apply { setMargins(dp(10),dp(8),dp(10),dp(8)) })
        destination(MenuGlyph.ACCOUNT,"Account/PC",profile)
        space(8,1f)
        return object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val density=context.resources.displayMetrics.density
                val width=MeasureSpec.getSize(widthMeasureSpec)/density
                val height=MeasureSpec.getSize(heightMeasureSpec)/density
                // Keep a calm centered column on tall phones; allow scrolling with large type.
                val side=maxOf(20f,width*.12f,(width-340f)/2f).toInt()
                content.setPadding(dp(side),0,dp(side),0)
                val rowHeight=dp(if(height>=680)56 else 48)
                rows.forEach { if(it.minimumHeight!=rowHeight)it.minimumHeight=rowHeight }
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply { isFillViewport=true; isVerticalScrollBarEnabled=false; setBackgroundColor(AppPalette.BACKGROUND); addView(content) }
    }

    fun placeholder(title: String, back: () -> Unit): View = scroll(column().apply {
        setPadding(dp(24),dp(32),dp(24),dp(24))
        addView(text(title,22f))
        addView(text("Not available yet",16f,true).apply { setPadding(0,dp(12),0,dp(24)) })
        addView(row("\uf104","Back to home",action=back))
    })
}
