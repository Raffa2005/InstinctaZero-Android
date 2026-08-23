package com.instinctazero.android.model

data class Device(
    val id: String,
    val name: String,
    val createdAt: String,
    val lastSeenAt: String? = null,
)

data class Account(val username: String)

data class ServerInfo(
    val apiVersion: String,
    val accountUsername: String,
)

data class PairingResult(
    val token: String,
    val device: Device,
    val server: ServerInfo,
)

sealed interface PairingState {
    data object Unpaired : PairingState
    data class Paired(val deviceId: String) : PairingState
    data class Ready(val session: SessionInfo) : PairingState
}

data class EngineCapabilities(
    val profiles: List<String>,
    val maxNodes: Int,
    val maxMultipv: Int,
)

data class SessionInfo(
    val device: Device,
    val account: Account,
    val sync: SyncState,
    val capabilities: EngineCapabilities,
)

data class SyncState(
    val running: Boolean = false,
    val status: String = "idle",
    val lastStartedAt: String? = null,
    val lastCompletedAt: String? = null,
    val lastError: String? = null,
    val gamesStored: Int = 0,
    val lastSeenAtMillis: Long = 0L,
)

data class Player(
    val name: String,
    val rating: Int? = null,
    val result: String? = null,
)

data class Opening(
    val eco: String,
    val name: String,
)

data class GameSummary(
    val id: String,
    val createdAtMillis: Long,
    val lastMoveAtMillis: Long,
    val status: String,
    val rated: Boolean,
    val speed: String,
    val perf: String,
    val variant: String,
    val white: Player,
    val black: Player,
    val result: String,
    val opening: Opening? = null,
    val plyCount: Int,
) {
    val isFinished: Boolean get() = status.lowercase() !in ACTIVE_GAME_STATUSES

    companion object {
        private val ACTIVE_GAME_STATUSES = setOf("created", "started")
    }
}

data class GameMove(
    val ply: Int,
    val uci: String,
    val san: String,
    val fenAfter: String,
)

data class GameDetail(
    val summary: GameSummary,
    val pgn: String,
    val initialFen: String,
    val moves: List<GameMove>,
) {
    fun fenAtPly(ply: Int): String? = when {
        ply == 0 -> initialFen
        ply < 0 -> null
        else -> moves.firstOrNull { it.ply == ply }?.fenAfter
    }
}

data class GamesPage(
    val games: List<GameSummary>,
    val nextCursor: String?,
)

data class RefreshResult(
    val page: GamesPage,
    val sync: SyncState,
    val newestNewGameId: String?,
)

data class ExplorerMove(
    val uci: String,
    val san: String,
    val white: Int,
    val draws: Int,
    val black: Int,
    val averageRating: Int? = null,
) {
    val games: Int get() = white + draws + black
}

data class ExplorerResult(
    val source: String,
    val fen: String,
    val moves: List<ExplorerMove>,
    val cached: Boolean,
)

data class AnalysisSettings(
    val nodes: Int = 1_000,
    val multipv: Int = 5,
    val profile: String = "exact-sycl",
)

enum class SearchPhase { PROVISIONAL, READY, FINAL }

data class SearchLine(
    val rank: Int,
    val score: String,
    val whiteCentipawns: Int? = null,
    val whiteMate: Int? = null,
    val depth: Int? = null,
    val selectiveDepth: Int? = null,
    val nodes: Long? = null,
    val nps: Long? = null,
    val pvUci: List<String>,
    val pvSan: List<String>,
    val visits: Long? = null,
    val prior: Double? = null,
    val visitShare: Double? = null,
)

data class SearchProgress(
    val visits: Long,
    val target: Long,
    val nps: Long?,
    val elapsedMillis: Long,
)

data class SearchSnapshot(
    val requestId: String,
    val fen: String,
    val snapshotId: Int,
    val phase: SearchPhase,
    val nodes: Long?,
    val targetNodes: Long,
    val elapsedMillis: Long,
    val profile: String,
    val approximate: Boolean,
    val lines: List<SearchLine>,
    val contextPly: Int? = null,
    val nps: Long? = null,
    val progress: SearchProgress? = null,
)

sealed interface AnalysisEvent {
    val requestId: String?

    data class Started(
        override val requestId: String,
        val fen: String,
        val targetNodes: Long,
        val profile: String,
        val approximate: Boolean,
        val contextPly: Int? = null,
    ) : AnalysisEvent

    data class Snapshot(val value: SearchSnapshot) : AnalysisEvent {
        override val requestId: String = value.requestId
    }

    data class Failed(
        override val requestId: String?,
        val engine: String,
        val message: String,
    ) : AnalysisEvent

    data class Completed(
        override val requestId: String?,
        val fen: String?,
        val cancelled: Boolean,
        val message: String,
        val contextPly: Int? = null,
        val finalSnapshot: SearchSnapshot? = null,
    ) : AnalysisEvent
}

data class LeelaValue(
    val ply: Int,
    val q: Double?,
    val sideToMove: String?,
    val sideToMoveExpectation: Double?,
    val drawProbability: Double?,
    val whiteWin: Double?,
    val whiteDraw: Double?,
    val whiteLoss: Double?,
    val wdlMuPawns: Double?,
    val elapsedMillis: Long?,
)

data class LeelaValues(
    val available: Boolean,
    val cached: Boolean,
    val values: List<LeelaValue>,
)

enum class GraphJobState {
    QUEUED,
    RUNNING,
    CANCELLING,
    COMMITTING,
    CANCELLED,
    COMPLETE,
    ERROR,
}

data class GraphJob(
    val jobId: String,
    val gameId: String,
    val state: GraphJobState,
    val createdAt: String,
    val updatedAt: String,
    val error: String? = null,
) {
    val isTerminal: Boolean
        get() = state == GraphJobState.CANCELLED || state == GraphJobState.COMPLETE || state == GraphJobState.ERROR
}

data class GraphStatus(
    val available: Boolean,
    val cached: Boolean,
    val settingsHash: String?,
    val values: LeelaValues?,
    val job: GraphJob?,
)

sealed class RepositoryFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotPaired : RepositoryFailure("This phone is not paired with InstinctaZero.")
    class Unauthorized : RepositoryFailure("The phone pairing was revoked or is no longer valid.")
    class Unavailable(message: String, cause: Throwable? = null) : RepositoryFailure(message, cause)
    class Server(val statusCode: Int, message: String) : RepositoryFailure(message)
    class InvalidResponse(message: String, cause: Throwable? = null) : RepositoryFailure(message, cause)
}
