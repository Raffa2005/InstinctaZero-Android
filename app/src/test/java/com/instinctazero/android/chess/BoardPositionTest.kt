package com.instinctazero.android.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardPositionTest {
    @Test
    fun `initial fen builds every piece on the expected square`() {
        val position = BoardPosition.fromFen(BoardPosition.INITIAL_FEN)

        assertEquals(32, position.pieces.size)
        assertEquals(PieceColor.WHITE, position.sideToMove)
        assertEquals(
            BoardPiece(PieceColor.WHITE, PieceType.KING),
            position.pieces[Square.parse("e1")],
        )
        assertEquals(
            BoardPiece(PieceColor.BLACK, PieceType.QUEEN),
            position.pieces[Square.parse("d8")],
        )
        assertNull(position.pieces[Square.parse("e4")])
    }

    @Test
    fun `fen parser handles compressed ranks and black to move`() {
        val position = BoardPosition.fromFen(
            "8/3k4/8/2pP4/8/8/4K3/8 b - - 0 40",
        )

        assertEquals(PieceColor.BLACK, position.sideToMove)
        assertEquals(4, position.pieces.size)
        assertEquals(
            BoardPiece(PieceColor.BLACK, PieceType.PAWN),
            position.pieces[Square.parse("c5")],
        )
        assertEquals(
            BoardPiece(PieceColor.WHITE, PieceType.PAWN),
            position.pieces[Square.parse("d5")],
        )
    }

    @Test
    fun `fen parser rejects malformed boards instead of rendering partial state`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoardPosition.fromFen("8/8/8/8/8/8/8/9 w - - 0 1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoardPosition.fromFen("8/8/8/8/8/8/8 w - - 0 1")
        }
        assertThrows(IllegalStateException::class.java) {
            BoardPosition.fromFen("8/8/8/8/8/8/8/4X3 w - - 0 1")
        }
    }

    @Test
    fun `square algebraic conversion is exact and rejects invalid coordinates`() {
        assertEquals(Square(0, 0), Square.parse("a1"))
        assertEquals(Square(7, 7), Square.parse("H8"))
        assertEquals("c6", Square(2, 5).algebraic)
        assertNull(Square.parse("i4"))
        assertNull(Square.parse("a9"))
        assertNull(Square.parse("e2e4"))
    }

    @Test
    fun `uci arrow accepts promotion suffix without treating it as a square`() {
        assertEquals(
            MoveArrow(Square.parse("e7")!!, Square.parse("e8")!!),
            MoveArrow.fromUci("e7e8q"),
        )
        assertNull(MoveArrow.fromUci("e2e"))
        assertNull(MoveArrow.fromUci("e2i4"))
    }
}
