package com.instinctazero.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One quiet list, shared by the home, drawer and repertoire screens. */
internal class WorkspaceMenu(private val context: Context) {
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density+.5f).toInt()
    fun text(label:String,size:Float=15f,muted:Boolean=false)=TextView(context).apply {
        text=label; textSize=size;setTextColor(if(muted)0xffacacac.toInt() else 0xffededed.toInt())
    }
    fun column()=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun section(label:String)=text(label,14f,true).apply {
        setPadding(0,dp(18),0,dp(4))
    }
    fun row(title:String,subtitle:String?=null,action:()->Unit):View=column().apply {
        minimumHeight=dp(48);gravity=Gravity.BOTTOM
        background=RippleDrawable(ColorStateList.valueOf(0x22ffffff),null,ColorDrawable(-1))
        isFocusable=true;isClickable=true;contentDescription=listOfNotNull(title,subtitle).joinToString(". ")
        addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(6),0,dp(6))
            addView(text(title,16f),LinearLayout.LayoutParams(0,-2,1f))
            if(!subtitle.isNullOrEmpty())addView(text(subtitle,12f,true).apply {
                gravity=Gravity.END;maxWidth=dp(120);setPadding(dp(8),0,0,0)
            })
        },LinearLayout.LayoutParams(-1,-2,1f))
        addView(View(context).apply { setBackgroundColor(0xff383838.toInt()) },LinearLayout.LayoutParams(-1,1))
        setOnClickListener { action() }
    }
    fun scroll(content:View)=ScrollView(context).apply { isFillViewport=true;addView(content) }
    fun home(paired:Boolean,connection:String,analysis:()->Unit,games:()->Unit,
        library:()->Unit,repertoire:()->Unit,profile:()->Unit,editor:()->Unit,privacy:View?=null):View=scroll(column().apply {
        setPadding(dp(20),dp(6),dp(20),dp(12))
        addView(row("Analysis board",action=analysis));addView(row("Board editor",action=editor))
        addView(row("Games",if(paired)null else "PC not paired",games))
        addView(View(context),LinearLayout.LayoutParams(1,dp(14)))
        addView(row("Repertoire library",action=library));addView(row("Repertoire board",action=repertoire))
        addView(View(context),LinearLayout.LayoutParams(1,dp(14)))
        addView(row("Account / PC",connection,profile))
        privacy?.let { addView(it) }
    })
}
