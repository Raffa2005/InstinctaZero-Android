package com.instinctazero.android.chess

enum class PieceColor { WHITE, BLACK }

enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class BoardPiece(val color: PieceColor, val type: PieceType)

data class Square(val file: Int, val rank: Int) {
    init {
        require(file in 0..7 && rank in 0..7) { "Square is outside the board" }
    }

    val algebraic: String get() = "${'a' + file}${rank + 1}"

    companion object {
        fun parse(value: String): Square? {
            if (value.length != 2) return null
            val file = value[0].lowercaseChar() - 'a'
            val rank = value[1] - '1'
            return if (file in 0..7 && rank in 0..7) Square(file, rank) else null
        }
    }
}

data class BoardPosition(
    val pieces: Map<Square, BoardPiece>,
    val sideToMove: PieceColor,
    val fen: String,
) {
    companion object {
        const val INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun fromFen(fen: String): BoardPosition {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size >= 2) { "FEN must include board and side to move" }
            val ranks = fields[0].split('/')
            require(ranks.size == 8) { "FEN must contain eight ranks" }

            val pieces = buildMap {
                ranks.forEachIndexed { rankIndex, encodedRank ->
                    var file = 0
                    encodedRank.forEach { symbol ->
                        if (symbol.isDigit()) {
                            file += symbol.digitToInt()
                        } else {
                            require(file in 0..7) { "Too many squares in FEN rank" }
                            put(
                                Square(file, 7 - rankIndex),
                                BoardPiece(
                                    color = if (symbol.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK,
                                    type = when (symbol.lowercaseChar()) {
                                        'k' -> PieceType.KING
                                        'q' -> PieceType.QUEEN
                                        'r' -> PieceType.ROOK
                                        'b' -> PieceType.BISHOP
                                        'n' -> PieceType.KNIGHT
                                        'p' -> PieceType.PAWN
                                        else -> error("Unknown FEN piece: $symbol")
                                    },
                                ),
                            )
                            file++
                        }
                    }
                    require(file == 8) { "FEN rank does not contain eight squares" }
                }
            }

            return BoardPosition(
                pieces = pieces,
                sideToMove = when (fields[1]) {
                    "w" -> PieceColor.WHITE
                    "b" -> PieceColor.BLACK
                    else -> error("Unknown side to move: ${fields[1]}")
                },
                fen = fen.trim(),
            )
        }
    }
}

data class MoveArrow(val from: Square, val to: Square) {
    companion object {
        fun fromUci(uci: String): MoveArrow? {
            if (uci.length !in 4..5) return null
            val from = Square.parse(uci.substring(0, 2)) ?: return null
            val to = Square.parse(uci.substring(2, 4)) ?: return null
            return MoveArrow(from, to)
        }
    }
}
