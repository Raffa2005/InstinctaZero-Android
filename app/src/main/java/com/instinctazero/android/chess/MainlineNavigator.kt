package com.instinctazero.android.chess

data class MainlinePly(
    val ply: Int,
    val san: String,
    val uci: String,
    val fenAfter: String,
)

class MainlineNavigator(
    private val initialFen: String,
    private val moves: List<MainlinePly>,
    startPly: Int = moves.size,
) {
    var currentPly: Int = startPly.coerceIn(0, moves.size)
        private set

    val position: BoardPosition
        get() = BoardPosition.fromFen(
            if (currentPly == 0) initialFen else moves[currentPly - 1].fenAfter,
        )

    val canGoBack: Boolean get() = currentPly > 0
    val canGoForward: Boolean get() = currentPly < moves.size

    fun first(): BoardPosition = seek(0)
    fun previous(): BoardPosition = seek(currentPly - 1)
    fun next(): BoardPosition = seek(currentPly + 1)
    fun last(): BoardPosition = seek(moves.size)

    fun seek(ply: Int): BoardPosition {
        currentPly = ply.coerceIn(0, moves.size)
        return position
    }
}
