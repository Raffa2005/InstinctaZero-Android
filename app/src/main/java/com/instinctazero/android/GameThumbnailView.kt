package com.instinctazero.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.caverock.androidsvg.SVG

/** Lightweight native preview using the exact bundled Cburnett artwork from the analysis board. */
internal class GameThumbnailView(
    context: Context,
    fen: String,
    orientation: String,
) : View(context) {
    private var blackAtBottom = orientation == "black"
    private var pieces = parseFen(fen)
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setPosition(fen: String, orientation: String) {
        blackAtBottom = orientation == "black"
        pieces = parseFen(fen)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        val square = side / 8f
        for (screenRank in 0..7) for (screenFile in 0..7) {
            squarePaint.color = if ((screenRank + screenFile) % 2 == 0) LIGHT else DARK
            canvas.drawRect(
                screenFile * square,
                screenRank * square,
                (screenFile + 1) * square,
                (screenRank + 1) * square,
                squarePaint,
            )
            val file = if (blackAtBottom) 7 - screenFile else screenFile
            val rank = if (blackAtBottom) screenRank else 7 - screenRank
            val piece = pieces[rank * 8 + file] ?: continue
            val bitmap = CburnettBitmapCache.get(context, piece) ?: continue
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    screenFile * square,
                    screenRank * square,
                    (screenFile + 1) * square,
                    (screenRank + 1) * square,
                ),
                piecePaint,
            )
        }
    }

    private fun parseFen(fen: String): Array<Char?> {
        val result = arrayOfNulls<Char>(64)
        val ranks = fen.substringBefore(' ').split('/')
        if (ranks.size != 8) return result
        ranks.forEachIndexed { fenRank, row ->
            var file = 0
            row.forEach { token ->
                if (token.isDigit()) file += token.digitToInt()
                else if (file in 0..7 && token in PIECE_NAMES) {
                    val rank = 7 - fenRank
                    result[rank * 8 + file] = token
                    file += 1
                }
            }
        }
        return result
    }

    companion object {
        private val LIGHT = 0xfff0d9b5.toInt()
        private val DARK = 0xffb58863.toInt()
        private val PIECE_NAMES = mapOf(
            'K' to "wK", 'Q' to "wQ", 'R' to "wR", 'B' to "wB", 'N' to "wN", 'P' to "wP",
            'k' to "bK", 'q' to "bQ", 'r' to "bR", 'b' to "bB", 'n' to "bN", 'p' to "bP",
        )

        private object CburnettBitmapCache {
            private const val RASTER_SIZE = 96
            private val bitmaps = mutableMapOf<Char, Bitmap>()

            @Synchronized
            fun get(context: Context, piece: Char): Bitmap? = bitmaps[piece] ?: runCatching {
                val name = requireNotNull(PIECE_NAMES[piece])
                val svg = SVG.getFromAsset(context.applicationContext.assets, "analysis/pieces/$name.svg")
                Bitmap.createBitmap(RASTER_SIZE, RASTER_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val canvas = Canvas(bitmap)
                    canvas.scale(RASTER_SIZE / 45f, RASTER_SIZE / 45f)
                    svg.renderToCanvas(canvas)
                    bitmaps[piece] = bitmap
                }
            }.getOrNull()
        }
    }
}
