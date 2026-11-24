package com.instinctazero.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared native menu language: grouped destinations, generous touch targets, quiet icons. */
internal class WorkspaceMenu(private val context: Context) {
    private val font = Typeface.createFromAsset(context.assets,"analysis/fonts/fontawesome-webfont.ttf")
    private val theme get() = UiTheme.current(context)
    private fun dp(n: Int) = (n*context.resources.displayMetrics.density+.5f).toInt()
    private fun surface(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun ripple(mask: GradientDrawable? = null) =
        RippleDrawable(ColorStateList.valueOf(theme.accentAlpha(0x2e)), null, mask)

    fun text(label: String, size: Float = 16f, muted: Boolean = false) = TextView(context).apply {
        text=label; textSize=size; setTextColor(if(muted)theme.muted else theme.text)
    }
    fun column() = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun section(label: String) = text(label.uppercase(),11f,true).apply {
        letterSpacing=.14f; setPadding(dp(4),dp(22),0,dp(9))
    }
    private fun iconTile(icon: String, size: Int, emphasized: Boolean = false) = LinearLayout(context).apply {
        gravity=Gravity.CENTER
        background=GradientDrawable().apply {
            shape=GradientDrawable.RECTANGLE; cornerRadius=dp(12).toFloat()
            setColor(theme.accentAlpha(if(emphasized)0x38 else 0x1e))
        }
        addView(text(icon,if(emphasized)21f else 19f).apply {
            typeface=font; gravity=Gravity.CENTER; setTextColor(theme.accent)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        layoutParams=LinearLayout.LayoutParams(dp(size),dp(size)).apply { marginEnd=dp(13) }
    }
    fun row(icon: String, title: String, subtitle: String? = null, action: () -> Unit): View = LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(13),dp(11),dp(12),dp(11)); minimumHeight=dp(if(subtitle==null)56 else 68)
        background=ripple(surface(-1,10))
        isClickable=true; isFocusable=true; contentDescription=listOfNotNull(title,subtitle).joinToString(". ")
        addView(iconTile(icon,40))
        addView(column().apply {
            addView(text(title,16f).apply { typeface=Typeface.DEFAULT_BOLD })
            if(subtitle!=null) addView(text(subtitle,12.5f,true).apply { setPadding(0,dp(2),0,0) })
        },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        addView(text("\uf105",15f,true).apply { typeface=font; gravity=Gravity.END; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(20),dp(30)))
        setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(6) }
    }
    fun scroll(content: View) = ScrollView(context).apply {
        isFillViewport=true; setBackgroundColor(theme.background); addView(content)
    }
    private fun themeSwatch(option: UiTheme, action: (UiTheme) -> Unit): View {
        val selected = theme == option
        return View(context).apply {
            background=GradientDrawable().apply {
                shape=GradientDrawable.OVAL
                setColor(option.accent)
                setStroke(dp(if(selected)2 else 1),if(selected)0xfff5f2ea.toInt() else 0x55ffffff)
            }
            contentDescription="${option.label} theme"
            isClickable=true; isFocusable=true; isSelected=selected
            setOnClickListener { UiTheme.select(context,option); action(option) }
            layoutParams=LinearLayout.LayoutParams(dp(32),dp(32)).apply { marginStart=dp(8) }
        }
    }
    private fun hero(paired: Boolean, connection: String, onTheme: (UiTheme) -> Unit): View = column().apply {
        background=GradientDrawable().apply {
            orientation=GradientDrawable.Orientation.TL_BR
            colors=intArrayOf(theme.heroStart,theme.heroEnd); cornerRadius=dp(20).toFloat()
            setStroke(dp(1),theme.stroke)
        }
        clipToOutline=true; setPadding(dp(18),dp(18),dp(18),dp(16))
        addView(LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.instinctazero_logo)
                scaleType=ImageView.ScaleType.CENTER_INSIDE; contentDescription=null
            },LinearLayout.LayoutParams(dp(52),dp(52)).apply { marginEnd=dp(14) })
            addView(column().apply {
                addView(text("InstinctaZero",22f).apply { typeface=Typeface.DEFAULT_BOLD; letterSpacing=.01f })
                addView(LinearLayout(context).apply {
                    orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                    addView(View(context).apply {
                        background=GradientDrawable().apply {
                            shape=GradientDrawable.OVAL
                            setColor(if(paired)0xff9fc27f.toInt() else 0xffd1a45c.toInt())
                        }
                    },LinearLayout.LayoutParams(dp(8),dp(8)).apply { marginEnd=dp(7) })
                    addView(text(connection,13f,true))
                }.apply { setPadding(0,dp(4),0,0) })
            },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        })
        addView(LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setPadding(0,dp(16),0,0)
            addView(text("APPEARANCE",11f,true).apply { letterSpacing=.14f },
                LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            for(option in UiTheme.entries) addView(themeSwatch(option,onTheme))
        })
        layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(14) }
    }
    private fun group(label: String, entries: List<View>): View = column().apply {
        addView(section(label))
        addView(column().apply {
            background=surface(theme.surface,16,theme.stroke); clipToOutline=true
            for((i,entry) in entries.withIndex()) {
                if(i>0)addView(View(context).apply { setBackgroundColor(theme.stroke) },
                    LinearLayout.LayoutParams(-1,dp(1)).apply { marginStart=dp(66);marginEnd=dp(12) })
                entry.background=ripple(surface(-1,8))
                addView(entry,LinearLayout.LayoutParams(-1,-2))
            }
        })
    }
    fun home(paired: Boolean, connection: String, analysis: () -> Unit, games: () -> Unit,
        library: () -> Unit, repertoire: () -> Unit, profile: () -> Unit, editor: () -> Unit,
        privacy: View? = null, onTheme: (UiTheme) -> Unit = {}): View = scroll(column().apply {
        setPadding(dp(16),0,dp(16),dp(20))
        addView(hero(paired,connection,onTheme))
        addView(section("Analysis"))
        addView(LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(15),dp(13),dp(13),dp(13)); minimumHeight=dp(84)
            background=RippleDrawable(ColorStateList.valueOf(theme.accentAlpha(0x2e)),
                GradientDrawable().apply {
                    orientation=GradientDrawable.Orientation.LEFT_RIGHT
                    colors=intArrayOf(theme.heroStart,theme.heroEnd); cornerRadius=dp(16).toFloat()
                    setStroke(dp(1),theme.accent)
                },null)
            clipToOutline=true
            isClickable=true; isFocusable=true; contentDescription="Analysis board. Continue where you left off"
            addView(iconTile("\uf201",46,true))
            addView(column().apply {
                addView(text("Analysis board",17f).apply { typeface=Typeface.DEFAULT_BOLD })
                addView(text("Continue where you left off",12.5f,true).apply { setPadding(0,dp(2),0,0) })
            },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            addView(text("\uf105",17f).apply { typeface=font; setTextColor(theme.accent); gravity=Gravity.END; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(22),dp(30)))
            setOnClickListener { analysis() }
        })
        addView(group("Explore",listOf(
            row("\uf044","Board editor",null,editor),
            row("\uf009","Games",if(paired)null else "Connect your PC to browse games",games))))
        addView(group("Repertoires",listOf(
            row("\uf02d","Repertoire library","Manage your openings",library),
            row("\uf279","Repertoire board",null,repertoire))))
        addView(group("Connection",listOf(row("\uf108","Account / PC",connection,profile))))
        privacy?.let { addView(it) }
    })
}
