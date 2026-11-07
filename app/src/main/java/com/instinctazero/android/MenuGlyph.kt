package com.instinctazero.android

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Small, local outline vectors. No font substitution, raster assets or icon dependency. */
internal enum class MenuGlyph(val path: String) {
    KNIGHT("M8 50 C9 43 15 37 23 31 L25 24 C21 29 16 31 12 32 L5 29 Q3 28 5 25 L14 13 L18 10 L19 4 L24 7 C38 12 44 27 41 50 Z"),
    ANALYSIS("M5 20 V13 M12 20 V8 M19 20 V3"),
    EDITOR("M4 3 H20 Q21 3 21 4 V20 Q21 21 20 21 H4 Q3 21 3 20 V4 Q3 3 4 3 Z M9 3 V21 M15 3 V21 M3 9 H21 M3 15 H21"),
    REPERTOIRES("M12 5 C9 3 5 3 2 5 V20 C5 18 9 18 12 20 C15 18 19 18 22 20 V5 C19 3 15 3 12 5 Z M12 5 V20"),
    PGN_LIBRARY("M8 3 H20 V17 H8 Z M4 7 V21 H16 M11 7 H17 M11 11 H17"),
    GAMES("M4 3 L13 10 L10 13 Z M3 15 L9 21 M6 18 L3 21 M20 3 L11 10 L14 13 Z M21 15 L15 21 M18 18 L21 21 M14 13 L18 17 M10 13 L6 17"),
    DRILL("M21 12 A9 9 0 1 1 12 3 M17 12 A5 5 0 1 1 12 7 M12 12 L21 3 M17 3 H21 V7"),
    ACCOUNT("M3 3 H21 V17 H3 Z M8 21 H16 M12 17 V21 M14.5 8 A2.5 2.5 0 1 1 9.5 8 A2.5 2.5 0 1 1 14.5 8 M7 14 C7 10 17 10 17 14"),
}

internal class MenuGlyphDrawable(glyph: MenuGlyph) : Drawable() {
    private val knight = glyph == MenuGlyph.KNIGHT
    private val path = PathParser.createPathFromPathData(glyph.path)!!
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppPalette.TEXT
        style = Paint.Style.STROKE
        strokeWidth = if (knight) 2.6f else 1.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val width = if (knight) 48f else 24f
        val height = if (knight) 56f else 24f
        val scale = minOf(bounds.width() / width, bounds.height() / height)
        val save = canvas.save()
        canvas.translate(bounds.left + (bounds.width() - width * scale) / 2, bounds.top + (bounds.height() - height * scale) / 2)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
