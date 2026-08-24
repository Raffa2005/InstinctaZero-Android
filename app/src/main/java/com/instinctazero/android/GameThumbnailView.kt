package com.instinctazero.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/** Lightweight native preview used only in the completed-game list. */
internal class GameThumbnailView(
    context: Context,
    fen: String,
    orientation: String,
) : View(context) {
    private val blackAtBottom = orientation == "black"
    private val pieces = parseFen(fen)
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.NORMAL)
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
            piecePaint.textSize = square * .86f
            piecePaint.color = if (piece.isUpperCase()) Color.WHITE else Color.BLACK
            piecePaint.setShadowLayer(square * .045f, 0f, square * .03f, if (piece.isUpperCase()) Color.BLACK else Color.WHITE)
            canvas.drawText(GLYPHS[piece] ?: "", (screenFile + .5f) * square, (screenRank + .79f) * square, piecePaint)
            piecePaint.clearShadowLayer()
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
                else if (file in 0..7 && token in GLYPHS) {
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
        private val GLYPHS = mapOf(
            'K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙",
            'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟",
        )
    }
}
