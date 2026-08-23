package com.instinctazero.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun AnalysisScreen(
    state: AnalysisUiState,
    onAction: (AnalysisAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LegacyColors.Background),
    ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val landscape = availableWidth > availableHeight
        Column(Modifier.fillMaxSize()) {
            AnalysisHeader(state, onAction)
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .background(LegacyColors.Background),
                    ) {
                        AnalysisBoard(state, onAction, Modifier.fillMaxSize())
                    }
                    AnalysisPanel(
                        state = state,
                        onAction = onAction,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                // Reserve the header plus a useful analysis pane on short portrait displays.
                val boardSize = (availableHeight - 284.dp).coerceAtLeast(160.dp).coerceAtMost(availableWidth)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(boardSize)
                        .background(LegacyColors.Background),
                    contentAlignment = Alignment.Center,
                ) {
                    AnalysisBoard(state, onAction, Modifier.size(boardSize))
                }
                AnalysisPanel(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AnalysisHeader(state: AnalysisUiState, onAction: (AnalysisAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(LegacyColors.Surface)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onAction(AnalysisAction.NavigateBack) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = LegacyColors.Text)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = state.title,
                color = LegacyColors.Text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.players,
                color = LegacyColors.Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnalysisBoard(
    state: AnalysisUiState,
    onAction: (AnalysisAction) -> Unit,
    modifier: Modifier,
) {
    ChessBoard(
        fen = state.fen,
        arrows = state.arrows,
        whiteAtBottom = state.whiteAtBottom,
        lastMoveFrom = state.lastMoveFrom,
        lastMoveTo = state.lastMoveTo,
        modifier = modifier,
        onSquareSelected = { onAction(AnalysisAction.InspectSquare(it)) },
    )
}

@Composable
private fun AnalysisPanel(
    state: AnalysisUiState,
    onAction: (AnalysisAction) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(LegacyColors.Background)) {
        AnalysisTabs(state.selectedTab) { onAction(AnalysisAction.SelectTab(it)) }
        HorizontalDivider(color = LegacyColors.Divider, thickness = 1.dp)
        state.message?.let { AnalysisMessage(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.selectedTab) {
                AnalysisTab.NOTATION -> NotationPane(state.moves, onAction)
                AnalysisTab.BOOK -> BookPane(state.bookMoves)
                AnalysisTab.LEELA -> Column(Modifier.fillMaxSize()) {
                    EngineStatus(state)
                    Box(Modifier.weight(1f).fillMaxWidth()) { LeelaPane(state, onAction) }
                }
                AnalysisTab.GRAPH -> GraphPane(state.evaluations, state.currentPly, onAction)
            }
        }
        BottomControls(onAction)
    }
}

@Composable
private fun AnalysisMessage(message: String) {
    Text(
        text = message,
        color = LegacyColors.Muted,
        fontSize = 10.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(LegacyColors.Surface).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun EngineStatus(state: AnalysisUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LegacyColors.SurfaceRaised),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(29.dp)
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = if (state.engineConnected) Color(0xFF7A9B4B) else LegacyColors.Muted,
            )
            Spacer(Modifier.width(4.dp))
            Text("Leela", color = LegacyColors.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Stat("depth", state.depth)
            Stat("n/s", state.nodesPerSecond)
            Stat("nodes", state.nodes)
            Stat("time", state.elapsed, trailing = false)
        }
        LinearProgressIndicator(
            progress = { state.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = LegacyColors.AccentStrong,
            trackColor = LegacyColors.Surface,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, trailing: Boolean = true) {
    Text(label, color = LegacyColors.Muted, fontSize = 9.sp)
    Spacer(Modifier.width(2.dp))
    Text(value, color = LegacyColors.Text, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    if (trailing) Spacer(Modifier.width(8.dp))
}

@Composable
private fun AnalysisTabs(selected: AnalysisTab, onTab: (AnalysisTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(LegacyColors.Surface),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabButton(AnalysisTab.NOTATION, Icons.Default.List, "Notation", selected, onTab)
        TabButton(AnalysisTab.LEELA, Icons.Default.ShowChart, "Leela lines", selected, onTab)
        TabButton(AnalysisTab.GRAPH, Icons.Default.AutoGraph, "Evaluation graph", selected, onTab)
        TabButton(AnalysisTab.BOOK, Icons.Default.MenuBook, "Opening book", selected, onTab)
    }
}

@Composable
private fun RowScope.TabButton(
    tab: AnalysisTab,
    icon: ImageVector,
    description: String,
    selected: AnalysisTab,
    onTab: (AnalysisTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .semantics {
                this.selected = selected == tab
                this.contentDescription = description
            }
            .clickable(role = Role.Tab) { onTab(tab) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (selected == tab) LegacyColors.Text else LegacyColors.Muted, modifier = Modifier.size(21.dp))
        if (selected == tab) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(.56f)
                    .height(2.dp)
                    .background(LegacyColors.Accent),
            )
        }
    }
}

@Composable
private fun LeelaPane(state: AnalysisUiState, onAction: (AnalysisAction) -> Unit) {
    val displayedLines = state.engineLines.filter { it.visits == null || it.visits > 0 }
    if (displayedLines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (state.engineThinking) "Waiting for the first visited move…" else "Leela is paused",
                color = LegacyColors.Muted,
                fontSize = 13.sp,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(displayedLines) { index, line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(AnalysisAction.SelectEngineLine(index)) }
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Leela line ${index + 1}, ${line.evaluation}, ${line.moves}"
                    }
                    .background(if (line.isPrimary) Color(0x101B8EC6) else Color.Transparent)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    line.evaluation,
                    color = if (line.isPrimary) LegacyColors.Text else LegacyColors.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(58.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        line.moves,
                        color = LegacyColors.Text,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    if (line.visits != null || line.policy != null) {
                        Text(
                            buildString {
                                line.visits?.let { append("${compactNumber(it)} visits") }
                                if (line.visits != null && line.policy != null) append("  ")
                                line.policy?.let { append("prior ${(it * 100).roundToInt()}%") }
                            },
                            color = LegacyColors.Muted,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
            HorizontalDivider(color = LegacyColors.Divider.copy(alpha = .65f), thickness = 1.dp)
        }
    }
}

@Composable
private fun NotationPane(moves: List<MovePair>, onAction: (AnalysisAction) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 3.dp)) {
        itemsIndexed(moves) { index, pair ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pair.number}.",
                    color = LegacyColors.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.width(38.dp).padding(start = 8.dp),
                )
                MoveCell(pair.white, pair.isCurrentWhite, Modifier.weight(1f)) {
                    onAction(AnalysisAction.SelectPly(index * 2 + 1))
                }
                MoveCell(pair.black, pair.isCurrentBlack, Modifier.weight(1f)) {
                    onAction(AnalysisAction.SelectPly(index * 2 + 2))
                }
            }
        }
    }
}

@Composable
private fun MoveCell(move: String, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .heightIn(min = 48.dp)
            .clickable(enabled = move.isNotEmpty(), onClick = onClick)
            .semantics { if (move.isNotEmpty()) contentDescription = "Move $move" }
            .background(if (current) LegacyColors.CurrentMove else Color.Transparent)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(move, color = LegacyColors.Text, fontSize = 13.sp)
    }
}

@Composable
private fun BookPane(moves: List<BookMove>) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(25.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Move", color = LegacyColors.Muted, fontSize = 9.sp, modifier = Modifier.width(61.dp))
            Text("Games", color = LegacyColors.Muted, fontSize = 9.sp, modifier = Modifier.width(62.dp))
            Text("Result", color = LegacyColors.Muted, fontSize = 9.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(moves) { _, move ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(move.move, color = LegacyColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(61.dp))
                    Text(compactNumber(move.games), color = LegacyColors.Muted, fontSize = 11.sp, modifier = Modifier.width(62.dp))
                    WdlBar(move, Modifier.weight(1f))
                }
                HorizontalDivider(color = LegacyColors.Divider.copy(alpha = .55f), thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun WdlBar(move: BookMove, modifier: Modifier) {
    Row(modifier.height(13.dp)) {
        if (move.whitePercent > 0) Box(Modifier.weight(move.whitePercent).fillMaxHeight().background(LegacyColors.WhiteWins))
        if (move.drawPercent > 0) Box(Modifier.weight(move.drawPercent).fillMaxHeight().background(LegacyColors.Draws))
        if (move.blackPercent > 0) Box(Modifier.weight(move.blackPercent).fillMaxHeight().background(LegacyColors.BlackWins))
    }
}

@Composable
private fun GraphPane(points: List<EvaluationPoint>, currentPly: Int, onAction: (AnalysisAction) -> Unit) {
    if (points.size < 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No evaluation history", color = LegacyColors.Muted, fontSize = 13.sp)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "White point of view",
            color = LegacyColors.Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .semantics {
                    contentDescription = "Leela evaluation graph"
                    stateDescription = "Current ply $currentPly"
                }
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val ratio = (tap.x / size.width).coerceIn(0f, 1f)
                        val index = (ratio * (points.size - 1)).roundToInt()
                        onAction(AnalysisAction.SelectPly(points[index].ply))
                    }
                },
        ) {
            val half = size.height / 2f
            drawLine(LegacyColors.Divider, Offset(0f, half), Offset(size.width, half), 1.dp.toPx())
            val graphPath = Path()
            points.forEachIndexed { index, point ->
                val x = index.toFloat() / (points.size - 1) * size.width
                val normalized = (point.value / 3f).coerceIn(-1f, 1f)
                val y = half - normalized * half * .86f
                if (index == 0) graphPath.moveTo(x, y) else graphPath.lineTo(x, y)
            }
            drawPath(graphPath, LegacyColors.Text, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            val selectedIndex = points.indices.minByOrNull { kotlin.math.abs(points[it].ply - currentPly) }
            selectedIndex?.let { index ->
                val point = points[index]
                val x = index.toFloat() / (points.size - 1) * size.width
                val normalized = (point.value / 3f).coerceIn(-1f, 1f)
                val y = half - normalized * half * .86f
                drawLine(LegacyColors.CurrentMove, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawCircle(LegacyColors.CurrentMove, radius = 4.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun BottomControls(onAction: (AnalysisAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(LegacyColors.Surface),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(Icons.Default.ListAlt, "Games") { onAction(AnalysisAction.OpenGames) }
        ControlButton(Icons.Default.Settings, "Settings") { onAction(AnalysisAction.OpenSettings) }
        ControlButton(Icons.Default.FlipCameraAndroid, "Flip board") { onAction(AnalysisAction.FlipBoard) }
        ControlButton(Icons.Default.NavigateBefore, "Previous move") { onAction(AnalysisAction.PreviousMove) }
        ControlButton(Icons.Default.NavigateNext, "Next move") { onAction(AnalysisAction.NextMove) }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.requiredSize(48.dp)) {
        Icon(icon, description, tint = LegacyColors.Muted, modifier = Modifier.size(22.dp))
    }
}

private fun compactNumber(number: Int): String = when {
    number >= 1_000_000 -> "${(number / 100_000f).roundToInt() / 10f}m"
    number >= 1_000 -> "${(number / 100f).roundToInt() / 10f}k"
    else -> number.toString()
}
