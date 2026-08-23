package com.instinctazero.android.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class AnalysisTab {
    NOTATION,
    BOOK,
    LEELA,
    GRAPH,
}

@Immutable
data class BoardArrow(
    val from: String,
    val to: String,
    val color: Color = Color(0xB078B9E7),
    val thickness: Float = 1f,
)

@Immutable
data class EngineLine(
    val evaluation: String,
    val moves: String,
    val visits: Int? = null,
    val policy: Double? = null,
    val isPrimary: Boolean = false,
    /** Root UCI used only for read-only engine-arrow rendering. */
    val firstMoveUci: String? = null,
    val visitShare: Float? = null,
)

@Immutable
data class BookMove(
    val move: String,
    val games: Int,
    val whitePercent: Float,
    val drawPercent: Float,
    val blackPercent: Float,
)

@Immutable
data class MovePair(
    val number: Int,
    val white: String,
    val black: String = "",
    val isCurrentWhite: Boolean = false,
    val isCurrentBlack: Boolean = false,
)

@Immutable
data class EvaluationPoint(
    val ply: Int,
    val value: Float,
)

@Immutable
data class AnalysisUiState(
    val title: String = "1+0 • Bullet",
    val players: String = "White (2100) × Black (2080)",
    val fen: String = "r2q1rk1/1p1b1ppp/p2bp3/3p1n2/1P1P4/2P1PN2/P2N1PPP/2RQ1RK1 w - - 2 13",
    val whiteAtBottom: Boolean = true,
    val lastMoveFrom: String? = "e7",
    val lastMoveTo: String? = "f5",
    val arrows: List<BoardArrow> = listOf(
        BoardArrow("d1", "a4"),
        BoardArrow("f3", "g5", Color(0x806E6E6E), .68f),
    ),
    val selectedTab: AnalysisTab = AnalysisTab.LEELA,
    val engineConnected: Boolean = true,
    val engineThinking: Boolean = true,
    val progress: Float = .64f,
    val depth: String = "19/22",
    val nodesPerSecond: String = "405",
    val nodes: String = "640k",
    val elapsed: String = "1.4s",
    val currentPly: Int = 24,
    val message: String? = null,
    val engineLines: List<EngineLine> = listOf(
        EngineLine("+0.42", "13. c4 Nf6 14. cxd5 exd5 15. Qc2 Re8 16. Rfd1", 183_422, .174, true),
        EngineLine("+0.31", "13. Nd4 Nxd4 14. cxd4 Rc8 15. Qa4 Qd7 16. Qxa6", 119_830, .132),
        EngineLine("+0.18", "13. Re1 Nf6 14. Bd3 Rc8 15. e4 dxe4 16. Nxe4", 86_104, .091),
    ),
    val bookMoves: List<BookMove> = listOf(
        BookMove("13. c4", 12_842, .42f, .31f, .27f),
        BookMove("13. Nd4", 8_116, .39f, .34f, .27f),
        BookMove("13. Re1", 3_907, .45f, .30f, .25f),
        BookMove("13. Qc2", 1_244, .41f, .35f, .24f),
    ),
    val moves: List<MovePair> = listOf(
        MovePair(1, "d4", "Nf6"), MovePair(2, "c4", "e6"), MovePair(3, "Nf3", "d5"),
        MovePair(4, "Nc3", "Be7"), MovePair(5, "Bf4", "O-O"), MovePair(6, "e3", "c5"),
        MovePair(7, "dxc5", "Bxc5"), MovePair(8, "Qc2", "Nc6"), MovePair(9, "a3", "Qa5"),
        MovePair(10, "Rd1", "Be7"), MovePair(11, "Rc1", "a6"), MovePair(12, "b4", "Qd8", false, true),
    ),
    val evaluations: List<EvaluationPoint> = listOf(
        EvaluationPoint(0, .08f), EvaluationPoint(2, .16f), EvaluationPoint(4, .12f),
        EvaluationPoint(6, .32f), EvaluationPoint(8, -.04f), EvaluationPoint(10, .21f),
        EvaluationPoint(12, .42f), EvaluationPoint(14, .31f), EvaluationPoint(16, .48f),
        EvaluationPoint(18, .28f), EvaluationPoint(20, .46f), EvaluationPoint(24, .42f),
    ),
)

sealed interface AnalysisAction {
    data object NavigateBack : AnalysisAction
    data object OpenGames : AnalysisAction
    data object ToggleEngine : AnalysisAction
    data object OpenSettings : AnalysisAction
    data object More : AnalysisAction
    data object Refresh : AnalysisAction
    data object FlipBoard : AnalysisAction
    data object FirstMove : AnalysisAction
    data object PreviousMove : AnalysisAction
    data object NextMove : AnalysisAction
    data object LastMove : AnalysisAction
    data class SelectTab(val tab: AnalysisTab) : AnalysisAction
    /** Read-only inspection hook for showing information about a square; never makes a move. */
    data class InspectSquare(val square: String) : AnalysisAction
    data class SelectPly(val ply: Int) : AnalysisAction
    data class SelectEngineLine(val index: Int) : AnalysisAction
}
