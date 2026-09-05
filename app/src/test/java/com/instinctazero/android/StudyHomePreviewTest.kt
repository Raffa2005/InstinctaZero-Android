package com.instinctazero.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyHomePreviewTest {
    @Test fun nativeStudyEntryFitsSmallPortraitPhoneWithoutInitializingNetworkOrCredentials() {
        for(width in listOf(360,390,412)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h640dp-mdpi")
            val activity=Robolectric.buildActivity(MainActivity::class.java).get()
            activity.setTheme(R.style.Theme_InstinctaZero)
            val content=MainActivity::class.java.getDeclaredMethod("homeContent",Boolean::class.javaPrimitiveType,String::class.java)
                .apply{isAccessible=true}.invoke(activity,true,"PC connected") as View
            content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(584,View.MeasureSpec.EXACTLY))
            content.layout(0,0,width,584)
            fun labels(view:View):List<TextView> = if(view is TextView) listOf(view) else if(view is ViewGroup) (0 until view.childCount).flatMap{labels(view.getChildAt(it))} else emptyList()
            assertTrue(labels(content).any{it.text.toString().contains("Studies / PGN")})
            val bitmap=Bitmap.createBitmap(width,584,Bitmap.Config.ARGB_8888);content.draw(Canvas(bitmap))
            val output=File("build/reports/study-preview/home-$width.png");output.parentFile!!.mkdirs()
            output.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val group=content as ViewGroup
            assertTrue(group.getChildAt(group.childCount-1).bottom<=584)
        }
    }
}
