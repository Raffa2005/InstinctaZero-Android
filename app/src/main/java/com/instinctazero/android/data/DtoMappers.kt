package com.instinctazero.android.data

import com.instinctazero.android.model.Account
import com.instinctazero.android.model.AnalysisEvent
import com.instinctazero.android.model.Device
import com.instinctazero.android.model.EngineCapabilities
import com.instinctazero.android.model.ExplorerMove
import com.instinctazero.android.model.ExplorerResult
import com.instinctazero.android.model.GameDetail
import com.instinctazero.android.model.GameMove
import com.instinctazero.android.model.GameSummary
import com.instinctazero.android.model.GamesPage
import com.instinctazero.android.model.GraphJob
import com.instinctazero.android.model.GraphJobState
import com.instinctazero.android.model.GraphStatus
import com.instinctazero.android.model.LeelaValue
import com.instinctazero.android.model.LeelaValues
import com.instinctazero.android.model.Opening
import com.instinctazero.android.model.PairingResult
import com.instinctazero.android.model.Player
import com.instinctazero.android.model.SearchLine
import com.instinctazero.android.model.SearchPhase
import com.instinctazero.android.model.SearchProgress
import com.instinctazero.android.model.SearchSnapshot
import com.instinctazero.android.model.ServerInfo
import com.instinctazero.android.model.SessionInfo
import com.instinctazero.android.model.SyncState

internal fun DeviceDto.toModel() = Device(id, name, createdAt, lastSeenAt)

internal fun PairClaimResponseDto.toModel() = PairingResult(
    token = token,
    device = device.toModel(),
    server = ServerInfo(server.apiVersion, server.accountUsername),
)

internal fun SyncStateDto.toModel() = SyncState(
    running = running,
    status = status,
    lastStartedAt = lastStartedAt,
    lastCompletedAt = lastCompletedAt,
    lastError = lastError,
    gamesStored = gamesStored,
    lastSeenAtMillis = lastSeenAtMillis,
)

internal fun SessionDto.toModel() = SessionInfo(
    device = device.toModel(),
    account = Account(account.username),
    sync = sync.toModel(),
    capabilities = EngineCapabilities(
        profiles = capabilities.profiles,
        maxNodes = capabilities.maxNodes,
        maxMultipv = capabilities.maxMultipv,
    ),
)

internal fun PlayerDto.toModel() = Player(name, rating, result)
internal fun OpeningDto.toModel() = Opening(eco, name)

internal fun GameSummaryDto.toModel() = GameSummary(
    id = id,
    createdAtMillis = createdAtMillis,
    lastMoveAtMillis = lastMoveAtMillis,
    status = status,
    rated = rated,
    speed = speed,
    perf = perf,
    variant = variant,
    white = white.toModel(),
    black = black.toModel(),
    result = result,
    opening = opening?.toModel(),
    plyCount = plyCount,
)

internal fun GameDetailDto.toModel() = GameDetail(
    summary = GameSummary(
        id = id,
        createdAtMillis = createdAtMillis,
        lastMoveAtMillis = lastMoveAtMillis,
        status = status,
        rated = rated,
        speed = speed,
        perf = perf,
        variant = variant,
        white = white.toModel(),
        black = black.toModel(),
        result = result,
        opening = opening?.toModel(),
        plyCount = plyCount,
    ),
    pgn = pgn,
    initialFen = initialFen,
    moves = moves.map { GameMove(it.ply, it.uci, it.san, it.fenAfter) },
)

internal fun GamesPageDto.toModel() = GamesPage(games.map { it.toModel() }, nextCursor)

internal fun ExplorerEnvelopeDto.toModel() = ExplorerResult(
    source = source,
    fen = fen,
    moves = payload.moves.map {
        ExplorerMove(it.uci, it.san, it.white, it.draws, it.black, it.averageRating)
    },
    cached = cached,
)

internal fun AnalysisStatusDto.toModel() = AnalysisEvent.Started(
    requestId = requestId,
    fen = fen,
    targetNodes = lc0Nodes,
    profile = lc0Profile,
    approximate = approximate,
    contextPly = ply,
)

internal fun Lc0SnapshotDto.toModel(): SearchSnapshot {
    val stats = moveStats.associateBy { it.uci }
    val totalPositiveVisits = moveStats.sumOf { it.visits }.takeIf { it > 0 }
    return SearchSnapshot(
        requestId = requestId,
        fen = fen,
        snapshotId = snapshotId.toInt(),
        phase = when (searchPhase) {
            "ready" -> SearchPhase.READY
            "final" -> SearchPhase.FINAL
            else -> SearchPhase.PROVISIONAL
        },
        nodes = totalNodes ?: nodes,
        targetNodes = targetNodes,
        elapsedMillis = elapsedMillis,
        profile = lc0Profile,
        approximate = approximate,
        lines = lines.map { line ->
            val stat = line.pv.firstOrNull()?.let(stats::get)
            SearchLine(
                rank = line.rank ?: line.multipv,
                score = line.score,
                whiteCentipawns = line.whiteCentipawns,
                whiteMate = line.whiteMate,
                depth = line.depth,
                selectiveDepth = line.seldepth,
                nodes = line.nodes,
                nps = line.nps,
                pvUci = line.pv,
                pvSan = line.san,
                visits = stat?.visits,
                prior = stat?.prior,
                visitShare = if (stat != null && totalPositiveVisits != null) {
                    stat.visits.toDouble() / totalPositiveVisits.toDouble()
                } else {
                    null
                },
            )
        },
        contextPly = ply,
        nps = nps,
        progress = progress?.let {
            SearchProgress(it.visits, it.target, it.nps, it.elapsedMillis)
        },
    )
}

internal fun LeelaValuesDto.toModel() = LeelaValues(
    available = available ?: values.isNotEmpty(),
    cached = cached,
    values = values.map {
        LeelaValue(
            ply = it.ply,
            q = it.value.q,
            sideToMove = it.value.sideToMove,
            sideToMoveExpectation = it.value.sideToMoveExpectation,
            drawProbability = it.value.drawProbability,
            whiteWin = it.value.whiteWdl?.win,
            whiteDraw = it.value.whiteWdl?.draw,
            whiteLoss = it.value.whiteWdl?.loss,
            wdlMuPawns = it.value.wdlMuPawns,
            elapsedMillis = it.elapsedMillis,
        )
    },
)

internal fun GraphJobDto.toModel() = GraphJob(
    jobId = jobId,
    gameId = gameId,
    state = when (state) {
        "queued" -> GraphJobState.QUEUED
        "running" -> GraphJobState.RUNNING
        "cancelling" -> GraphJobState.CANCELLING
        "committing" -> GraphJobState.COMMITTING
        "cancelled" -> GraphJobState.CANCELLED
        "complete" -> GraphJobState.COMPLETE
        else -> GraphJobState.ERROR
    },
    createdAt = createdAt,
    updatedAt = updatedAt,
    error = error,
)

internal fun GraphStatusDto.toModel(): GraphStatus {
    val mappedValues = if (values.isNotEmpty() || available) {
        LeelaValuesDto(available, cached, values).toModel()
    } else {
        null
    }
    return GraphStatus(available, cached, settingsHash, mappedValues, job?.toModel())
}

internal fun GraphStatusDto.valuesDto() = LeelaValuesDto(available, cached, values)
