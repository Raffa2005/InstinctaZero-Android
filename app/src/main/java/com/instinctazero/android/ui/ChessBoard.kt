package com.instinctazero.android.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.hypot

private data class BoardPiece(
    val square: String,
    val piece: Char,
)

@Composable
fun ChessBoard(
    fen: String,
    arrows: List<BoardArrow>,
    whiteAtBottom: Boolean,
    lastMoveFrom: String?,
    lastMoveTo: String?,
    modifier: Modifier = Modifier,
    onSquareSelected: (String) -> Unit = {},
) {
    val pieces = parseFen(fen)
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Chess board" }
            .pointerInput(whiteAtBottom) {
                detectTapGestures { tap ->
                    val cell = size.width / 8f
                    val visualFile = (tap.x / cell).toInt().coerceIn(0, 7)
                    val visualRank = (tap.y / cell).toInt().coerceIn(0, 7)
                    val file = if (whiteAtBottom) visualFile else 7 - visualFile
                    val rank = if (whiteAtBottom) 8 - visualRank else visualRank + 1
                    onSquareSelected("${('a'.code + file).toChar()}$rank")
                }
            },
    ) {
        val cell = size.width / 8f
        for (visualRank in 0 until 8) {
            for (visualFile in 0 until 8) {
                val file = if (whiteAtBottom) visualFile else 7 - visualFile
                val rank = if (whiteAtBottom) 8 - visualRank else visualRank + 1
                val square = "${('a'.code + file).toChar()}$rank"
                val isLight = (file + rank) % 2 == 1
                val isLastMove = square == lastMoveFrom || square == lastMoveTo
                val color = if (isLight) LegacyColors.BoardLight else LegacyColors.BoardDark
                drawRect(
                    color = color,
                    topLeft = Offset(visualFile * cell, visualRank * cell),
                    size = Size(cell, cell),
                )
                if (isLastMove) {
                    drawRect(
                        color = if (isLight) LegacyColors.LastMoveLight else LegacyColors.LastMoveDark,
                        topLeft = Offset(visualFile * cell, visualRank * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }

        arrows.forEach { arrow -> drawBoardArrow(arrow, whiteAtBottom, cell) }
        pieces.forEach { piece ->
            squareCenter(piece.square, whiteAtBottom, cell)?.let { center ->
                drawCburnettPiece(piece.piece, center, cell)
            }
        }
        drawCoordinates(whiteAtBottom, cell)
    }
}

private fun parseFen(fen: String): List<BoardPiece> {
    val placement = fen.substringBefore(' ')
    val rows = placement.split('/')
    if (rows.size != 8) return emptyList()
    val result = mutableListOf<BoardPiece>()
    val pieces = "KQRBNPkqrbnp"
    rows.forEachIndexed { rowIndex, row ->
        var file = 0
        row.forEach { token ->
            if (token.isDigit()) {
                file += token.digitToInt()
            } else {
                if (token in pieces) {
                    result += BoardPiece(
                        square = "${('a'.code + file).toChar()}${8 - rowIndex}",
                        piece = token,
                    )
                }
                file += 1
            }
        }
    }
    return result
}

private fun DrawScope.drawCoordinates(whiteAtBottom: Boolean, cell: Float) {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = cell * .16f
            typeface = Typeface.DEFAULT_BOLD
        }
        for (visualFile in 0 until 8) {
            val file = if (whiteAtBottom) visualFile else 7 - visualFile
            val isLight = (file + if (whiteAtBottom) 1 else 8) % 2 == 1
            paint.color = if (isLight) 0xFF9D7152.toInt() else 0xFFE7CBA5.toInt()
            paint.textAlign = Paint.Align.RIGHT
            canvas.nativeCanvas.drawText(
                ('a'.code + file).toChar().toString(),
                (visualFile + 1) * cell - cell * .06f,
                size.height - cell * .05f,
                paint,
            )
        }
        for (visualRank in 0 until 8) {
            val rank = if (whiteAtBottom) 8 - visualRank else visualRank + 1
            val fileForColor = if (whiteAtBottom) 0 else 7
            val isLight = (fileForColor + rank) % 2 == 1
            paint.color = if (isLight) 0xFF9D7152.toInt() else 0xFFE7CBA5.toInt()
            paint.textAlign = Paint.Align.LEFT
            canvas.nativeCanvas.drawText(
                rank.toString(),
                cell * .045f,
                visualRank * cell + cell * .18f,
                paint,
            )
        }
    }
}

private fun DrawScope.drawBoardArrow(arrow: BoardArrow, whiteAtBottom: Boolean, cell: Float) {
    val from = squareCenter(arrow.from, whiteAtBottom, cell) ?: return
    val destination = squareCenter(arrow.to, whiteAtBottom, cell) ?: return
    val dx = destination.x - from.x
    val dy = destination.y - from.y
    val length = hypot(dx, dy)
    if (length < cell * .3f) return
    val ux = dx / length
    val uy = dy / length
    val headLength = cell * .38f
    val lineEnd = Offset(destination.x - ux * headLength * .62f, destination.y - uy * headLength * .62f)
    val thickness = arrow.thickness.coerceIn(.35f, 1.5f)
    drawLine(
        color = arrow.color,
        start = from,
        end = lineEnd,
        strokeWidth = cell * .16f * thickness,
        cap = StrokeCap.Round,
    )
    val perpendicularX = -uy
    val perpendicularY = ux
    val base = Offset(destination.x - ux * headLength, destination.y - uy * headLength)
    val halfWidth = cell * .29f * thickness.coerceAtLeast(.72f)
    val head = Path().apply {
        moveTo(destination.x, destination.y)
        lineTo(base.x + perpendicularX * halfWidth, base.y + perpendicularY * halfWidth)
        lineTo(base.x - perpendicularX * halfWidth, base.y - perpendicularY * halfWidth)
        close()
    }
    drawPath(head, arrow.color)
}

private fun squareCenter(square: String, whiteAtBottom: Boolean, cell: Float): Offset? {
    if (square.length != 2 || square[0] !in 'a'..'h' || square[1] !in '1'..'8') return null
    val file = square[0] - 'a'
    val rank = square[1].digitToInt()
    val visualFile = if (whiteAtBottom) file else 7 - file
    val visualRank = if (whiteAtBottom) 8 - rank else rank - 1
    return Offset((visualFile + .5f) * cell, (visualRank + .5f) * cell)
}
