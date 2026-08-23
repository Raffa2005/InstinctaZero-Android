package com.instinctazero.android.data

import com.instinctazero.android.model.ExplorerMove
import com.instinctazero.android.model.ExplorerResult
import com.instinctazero.android.model.GameDetail
import com.instinctazero.android.model.GameMove
import com.instinctazero.android.model.GameSummary
import com.instinctazero.android.model.LeelaValue
import com.instinctazero.android.model.LeelaValues
import com.instinctazero.android.model.Opening
import com.instinctazero.android.model.Player
import com.instinctazero.android.model.SearchLine
import com.instinctazero.android.model.SearchPhase
import com.instinctazero.android.model.SearchSnapshot

/** Deterministic native-preview data; never selected by production repositories. */
object DemoFixtures {
    val game = GameDetail(
        summary = GameSummary(
            id = "demo-sicilian",
            createdAtMillis = 1_758_000_000_000,
            lastMoveAtMillis = 1_758_000_480_000,
            status = "resign",
            rated = true,
            speed = "blitz",
            perf = "blitz",
            variant = "standard",
            white = Player("Rafael", 2148, "win"),
            black = Player("Opponent", 2181, "loss"),
            result = "1-0",
            opening = Opening("B90", "Sicilian Defense: Najdorf Variation"),
            plyCount = 8,
        ),
        pgn = "1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 1-0",
        initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        moves = listOf(
            GameMove(1, "e2e4", "e4", "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"),
            GameMove(2, "c7c5", "c5", "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"),
            GameMove(3, "g1f3", "Nf3", "rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"),
            GameMove(4, "d7d6", "d6", "rnbqkbnr/pp2pppp/3p4/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3"),
            GameMove(5, "d2d4", "d4", "rnbqkbnr/pp2pppp/3p4/2p5/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3"),
            GameMove(6, "c5d4", "cxd4", "rnbqkbnr/pp2pppp/3p4/8/3pP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 0 4"),
            GameMove(7, "f3d4", "Nxd4", "rnbqkbnr/pp2pppp/3p4/8/3NP3/8/PPP2PPP/RNBQKB1R b KQkq - 0 4"),
            GameMove(8, "g8f6", "Nf6", "rnbqkb1r/pp2pppp/3p1n2/8/3NP3/8/PPP2PPP/RNBQKB1R w KQkq - 1 5"),
        ),
    )

    val explorer = ExplorerResult(
        source = "masters",
        fen = game.moves[3].fenAfter,
        cached = false,
        moves = listOf(
            ExplorerMove("d2d4", "d4", 29_436, 31_187, 22_950, 2447),
            ExplorerMove("f1b5", "Bb5+", 3_990, 4_165, 3_289, 2431),
            ExplorerMove("c2c3", "c3", 2_852, 2_931, 2_144, 2419),
        ),
    )

    val search = SearchSnapshot(
        requestId = "demo-search",
        fen = game.moves.last().fenAfter,
        snapshotId = 12,
        phase = SearchPhase.READY,
        nodes = 742,
        targetNodes = 1_000,
        elapsedMillis = 8_412,
        profile = "exact-sycl",
        approximate = false,
        lines = listOf(
            SearchLine(1, "+0.31", 31, pvUci = listOf("b1c3", "e7e5", "d4b5"), pvSan = listOf("Nc3", "e5", "Nb5"), visits = 284, prior = .18, visitShare = .38),
            SearchLine(2, "+0.18", 18, pvUci = listOf("f2f3", "e7e5", "d4b3"), pvSan = listOf("f3", "e5", "Nb3"), visits = 176, prior = .12, visitShare = .24),
            SearchLine(3, "+0.11", 11, pvUci = listOf("c2c4", "e7e5", "d4b5"), pvSan = listOf("c4", "e5", "Nb5"), visits = 121, prior = .09, visitShare = .16),
        ),
    )

    val values = LeelaValues(
        available = true,
        cached = true,
        values = listOf(
            LeelaValue(0, .08, "white", .54, .42, .33, .42, .25, .12, 900),
            LeelaValue(2, .02, "white", .51, .45, .285, .45, .265, .03, 900),
            LeelaValue(4, .11, "white", .555, .40, .355, .40, .245, .16, 900),
            LeelaValue(6, -.04, "white", .48, .43, .265, .43, .305, -.06, 900),
            LeelaValue(8, .06, "white", .53, .41, .325, .41, .265, .09, 900),
        ),
    )
}
