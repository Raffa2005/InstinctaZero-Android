package com.instinctazero.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun `analysis defaults preserve exact sycl as the selected backend`() {
        val settings = AnalysisSettings()

        assertEquals("exact-sycl", settings.profile)
        assertEquals(1_000, settings.nodes)
        assertEquals(5, settings.multipv)
    }

    @Test
    fun `game detail resolves initial and exact stored ply positions only`() {
        val detail = GameDetail(
            summary = GameSummary(
                id = "game-1",
                createdAtMillis = 100,
                lastMoveAtMillis = 200,
                status = "mate",
                rated = true,
                speed = "blitz",
                perf = "blitz",
                variant = "standard",
                white = Player("White"),
                black = Player("Black"),
                result = "1-0",
                plyCount = 2,
            ),
            pgn = "1. e4 e5 1-0",
            initialFen = "initial",
            moves = listOf(
                GameMove(1, "e2e4", "e4", "after-e4"),
                GameMove(2, "e7e5", "e5", "after-e5"),
            ),
        )

        assertEquals("initial", detail.fenAtPly(0))
        assertEquals("after-e4", detail.fenAtPly(1))
        assertEquals("after-e5", detail.fenAtPly(2))
        assertNull(detail.fenAtPly(-1))
        assertNull(detail.fenAtPly(3))
    }

    @Test
    fun `explorer total includes decisive games and draws`() {
        val move = ExplorerMove(
            uci = "e2e4",
            san = "e4",
            white = 31,
            draws = 17,
            black = 22,
        )

        assertEquals(70, move.games)
    }

    @Test
    fun `only server active statuses prevent completed-game analysis`() {
        fun game(status: String) = GameSummary(
            id = status,
            createdAtMillis = 1,
            lastMoveAtMillis = 2,
            status = status,
            rated = false,
            speed = "rapid",
            perf = "rapid",
            variant = "standard",
            white = Player("White"),
            black = Player("Black"),
            result = "*",
            plyCount = 1,
        )

        assertFalse(game("created").isFinished)
        assertFalse(game("STARTED").isFinished)
        assertTrue(game("mate").isFinished)
        assertTrue(game("resign").isFinished)
        assertTrue(game("outoftime").isFinished)
        assertTrue(game("draw").isFinished)
    }
}
