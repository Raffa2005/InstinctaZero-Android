package com.instinctazero.android

import android.content.Context

/**
 * Named appearance palettes shared by the native shell and the analysis page.
 * The analysis page reads the same "appearance" preference through getUiSettings.
 */
internal enum class UiTheme(
    val key: String,
    val label: String,
    val background: Int,
    val header: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val stroke: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val heroStart: Int,
    val heroEnd: Int,
) {
    BROWN(
        "brown", "Ember",
        background = 0xff25241f.toInt(),
        header = 0xff1d1c19.toInt(),
        surface = 0xff302f2a.toInt(),
        surfaceAlt = 0xff39372f.toInt(),
        stroke = 0xff4b493e.toInt(),
        text = 0xfff2f0e8.toInt(),
        muted = 0xffa8a597.toInt(),
        accent = 0xffd0b87c.toInt(),
        onAccent = 0xff27231a.toInt(),
        heroStart = 0xff453b27.toInt(),
        heroEnd = 0xff2a2720.toInt(),
    ),
    BLUE(
        "blue", "Ocean",
        background = 0xff22262c.toInt(),
        header = 0xff1a1d22.toInt(),
        surface = 0xff2c3038.toInt(),
        surfaceAlt = 0xff333842.toInt(),
        stroke = 0xff454c59.toInt(),
        text = 0xffedf1f6.toInt(),
        muted = 0xff9da7b5.toInt(),
        accent = 0xff8fb4e2.toInt(),
        onAccent = 0xff1c2431.toInt(),
        heroStart = 0xff2c3d55.toInt(),
        heroEnd = 0xff232730.toInt(),
    ),
    GREEN(
        "green", "Pine",
        background = 0xff222824.toInt(),
        header = 0xff1a1f1b.toInt(),
        surface = 0xff2b332d.toInt(),
        surfaceAlt = 0xff333c35.toInt(),
        stroke = 0xff47544a.toInt(),
        text = 0xffeef3ec.toInt(),
        muted = 0xffa1aea2.toInt(),
        accent = 0xffa6c98d.toInt(),
        onAccent = 0xff202a1c.toInt(),
        heroStart = 0xff31442f.toInt(),
        heroEnd = 0xff222a23.toInt(),
    ),
    GREY(
        "grey", "Slate",
        background = 0xff252628.toInt(),
        header = 0xff1c1d1f.toInt(),
        surface = 0xff2f3033.toInt(),
        surfaceAlt = 0xff37383c.toInt(),
        stroke = 0xff4a4c50.toInt(),
        text = 0xfff0f0f2.toInt(),
        muted = 0xffa5a7ac.toInt(),
        accent = 0xffc2c9d6.toInt(),
        onAccent = 0xff232529.toInt(),
        heroStart = 0xff3a3d44.toInt(),
        heroEnd = 0xff26272a.toInt(),
    );

    /** Accent with a translucent alpha for ripples and soft fills. */
    fun accentAlpha(alpha: Int): Int = (accent and 0x00ffffff) or (alpha shl 24)

    companion object {
        private const val PREFS = "study_ui_settings"
        private const val KEY = "appearance"
        fun current(context: Context): UiTheme {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, BROWN.key)
            return entries.firstOrNull { it.key == stored } ?: BROWN
        }
        fun select(context: Context, theme: UiTheme) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.key).apply()
        }
    }
}
