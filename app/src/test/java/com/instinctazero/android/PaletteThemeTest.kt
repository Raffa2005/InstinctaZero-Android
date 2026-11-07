package com.instinctazero.android

import android.util.TypedValue
import android.view.ContextThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class PaletteThemeTest {
    @Test fun nativeThemeAndSystemBarsShareTheNeutralPaletteOnOldAndNewAndroid() {
        val context=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_InstinctaZero)
        fun value(attribute: Int)=TypedValue().also { context.theme.resolveAttribute(attribute,it,true) }.data
        assertEquals(AppPalette.BACKGROUND,value(android.R.attr.colorBackground))
        assertEquals(AppPalette.BACKGROUND,value(android.R.attr.statusBarColor))
        assertEquals(AppPalette.BACKGROUND,value(android.R.attr.navigationBarColor))
        assertEquals(AppPalette.SURFACE,value(android.R.attr.colorBackgroundFloating))
        assertEquals(AppPalette.CONTROL,value(android.R.attr.colorButtonNormal))
        assertEquals(AppPalette.TEXT,value(android.R.attr.textColorPrimary))
        assertEquals(AppPalette.MUTED,value(android.R.attr.textColorSecondary))
    }
}
