package com.instinctazero.android.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainlineNavigatorTest {
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val afterE5 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private val afterNf3 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"

    private fun navigator(startPly: Int = 3) = MainlineNavigator(
        initialFen = BoardPosition.INITIAL_FEN,
        moves = listOf(
            MainlinePly(1, "e4", "e2e4", afterE4),
            MainlinePly(2, "e5", "e7e5", afterE5),
            MainlinePly(3, "Nf3", "g1f3", afterNf3),
        ),
        startPly = startPly,
    )

    @Test
    fun `navigation walks immutable mainline positions in both directions`() {
        val navigator = navigator()

        assertEquals(3, navigator.currentPly)
        assertEquals(afterNf3, navigator.position.fen)
        assertTrue(navigator.canGoBack)
        assertFalse(navigator.canGoForward)

        assertEquals(afterE5, navigator.previous().fen)
        assertEquals(2, navigator.currentPly)
        assertEquals(afterE4, navigator.previous().fen)
        assertEquals(afterE5, navigator.next().fen)
        assertEquals(afterNf3, navigator.last().fen)
        assertEquals(BoardPosition.INITIAL_FEN, navigator.first().fen)
    }

    @Test
    fun `navigation clamps every boundary without indexing outside the line`() {
        val navigator = navigator(startPly = 99)

        assertEquals(3, navigator.currentPly)
        assertEquals(afterNf3, navigator.next().fen)
        assertEquals(3, navigator.currentPly)

        assertEquals(BoardPosition.INITIAL_FEN, navigator.seek(-20).fen)
        assertEquals(0, navigator.currentPly)
        assertEquals(BoardPosition.INITIAL_FEN, navigator.previous().fen)
        assertEquals(0, navigator.currentPly)
    }

    @Test
    fun `empty mainline always resolves to initial position`() {
        val navigator = MainlineNavigator(BoardPosition.INITIAL_FEN, emptyList())

        assertEquals(BoardPosition.INITIAL_FEN, navigator.position.fen)
        assertFalse(navigator.canGoBack)
        assertFalse(navigator.canGoForward)
        assertEquals(BoardPosition.INITIAL_FEN, navigator.last().fen)
    }
}
