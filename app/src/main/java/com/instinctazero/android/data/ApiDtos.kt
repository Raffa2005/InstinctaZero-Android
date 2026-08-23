package com.instinctazero.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PairClaimRequestDto(
    val code: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
internal data class PairClaimResponseDto(
    val token: String,
    val device: DeviceDto,
    val server: ServerDto,
)

@Serializable
internal data class DeviceDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
internal data class ServerDto(
    @SerialName("api_version") val apiVersion: String,
    @SerialName("account_username") val accountUsername: String,
)

@Serializable
internal data class AccountDto(val username: String)

@Serializable
internal data class CapabilitiesDto(
    val profiles: List<String> = emptyList(),
    @SerialName("max_nodes") val maxNodes: Int = 100_000,
    @SerialName("max_multipv") val maxMultipv: Int = 8,
)

@Serializable
internal data class SessionDto(
    val device: DeviceDto,
    val account: AccountDto,
    val sync: SyncStateDto = SyncStateDto(),
    val capabilities: CapabilitiesDto = CapabilitiesDto(),
)

@Serializable
internal data class SyncEnvelopeDto(val sync: SyncStateDto)

@Serializable
internal data class SyncStateDto(
    val running: Boolean = false,
    val status: String = "idle",
    @SerialName("last_started_at") val lastStartedAt: String? = null,
    @SerialName("last_completed_at") val lastCompletedAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("games_stored") val gamesStored: Int = 0,
    @SerialName("last_seen_at_ms") val lastSeenAtMillis: Long = 0L,
)

@Serializable
internal data class PlayerDto(
    val name: String,
    val rating: Int? = null,
    val result: String? = null,
)

@Serializable
internal data class OpeningDto(
    val eco: String,
    val name: String,
)

@Serializable
internal data class GameSummaryDto(
    val id: String,
    @SerialName("created_at_ms") val createdAtMillis: Long,
    @SerialName("last_move_at_ms") val lastMoveAtMillis: Long,
    val status: String,
    val rated: Boolean,
    val speed: String,
    val perf: String,
    val variant: String,
    val white: PlayerDto,
    val black: PlayerDto,
    val result: String,
    val opening: OpeningDto? = null,
    @SerialName("ply_count") val plyCount: Int,
)

@Serializable
internal data class GamesPageDto(
    val games: List<GameSummaryDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class GameMoveDto(
    val ply: Int,
    val uci: String,
    val san: String,
    @SerialName("fen_after") val fenAfter: String,
)

@Serializable
internal data class GameDetailDto(
    val id: String,
    @SerialName("created_at_ms") val createdAtMillis: Long,
    @SerialName("last_move_at_ms") val lastMoveAtMillis: Long,
    val status: String,
    val rated: Boolean,
    val speed: String,
    val perf: String,
    val variant: String,
    val white: PlayerDto,
    val black: PlayerDto,
    val result: String,
    val opening: OpeningDto? = null,
    @SerialName("ply_count") val plyCount: Int,
    val pgn: String,
    @SerialName("initial_fen") val initialFen: String,
    val moves: List<GameMoveDto> = emptyList(),
)

@Serializable
internal data class GameEnvelopeDto(val game: GameDetailDto)

@Serializable
internal data class ExplorerEnvelopeDto(
    val source: String,
    val fen: String,
    val payload: ExplorerPayloadDto = ExplorerPayloadDto(),
    val cached: Boolean = false,
)

@Serializable
internal data class ExplorerPayloadDto(
    val white: Int = 0,
    val draws: Int = 0,
    val black: Int = 0,
    val moves: List<ExplorerMoveDto> = emptyList(),
)

@Serializable
internal data class ExplorerMoveDto(
    val uci: String,
    val san: String,
    val white: Int = 0,
    val draws: Int = 0,
    val black: Int = 0,
    val averageRating: Int? = null,
)

@Serializable
internal data class AnalysisStatusDto(
    val message: String = "started",
    @SerialName("request_id") val requestId: String,
    val fen: String,
    @SerialName("lc0_nodes") val lc0Nodes: Long = 0,
    @SerialName("lc0_profile") val lc0Profile: String = "exact-sycl",
    val approximate: Boolean = false,
    val ply: Int? = null,
)

@Serializable
internal data class AnalysisLineDto(
    val multipv: Int = 1,
    val rank: Int? = null,
    val score: String = "n/a",
    @SerialName("white_cp") val whiteCentipawns: Int? = null,
    @SerialName("white_mate") val whiteMate: Int? = null,
    val depth: Int? = null,
    val seldepth: Int? = null,
    val nodes: Long? = null,
    val nps: Long? = null,
    val pv: List<String> = emptyList(),
    val san: List<String> = emptyList(),
)

@Serializable
internal data class MoveStatDto(
    val uci: String,
    val san: String? = null,
    val visits: Long = 0,
    @SerialName("in_flight") val inFlight: Long = 0,
    val prior: Double? = null,
    val wl: Double? = null,
    val draw: Double? = null,
    @SerialName("moves_left") val movesLeft: Double? = null,
    val q: Double? = null,
    val u: Double? = null,
    val s: Double? = null,
    val v: Double? = null,
)

@Serializable
internal data class Lc0SnapshotDto(
    val engine: String = "lc0",
    val ok: Boolean = true,
    val nodes: Long? = null,
    @SerialName("total_nodes") val totalNodes: Long? = null,
    @SerialName("target_nodes") val targetNodes: Long = 0,
    val multipv: Int = 1,
    @SerialName("elapsed_ms") val elapsedMillis: Long = 0,
    val lines: List<AnalysisLineDto> = emptyList(),
    @SerialName("move_stats") val moveStats: List<MoveStatDto> = emptyList(),
    @SerialName("root_move_stats") val rootMoveStats: List<MoveStatDto> = emptyList(),
    @SerialName("lc0_profile") val lc0Profile: String = "exact-sycl",
    val approximate: Boolean = false,
    @SerialName("search_phase") val searchPhase: String = "provisional",
    @SerialName("snapshot_id") val snapshotId: Long = 0,
    @SerialName("request_id") val requestId: String,
    val fen: String,
    val ply: Int? = null,
    val nps: Long? = null,
    val progress: AnalysisProgressDto? = null,
)

@Serializable
internal data class AnalysisProgressDto(
    val visits: Long = 0,
    val target: Long = 0,
    val nps: Long? = null,
    @SerialName("elapsed_ms") val elapsedMillis: Long = 0,
)

@Serializable
internal data class EngineErrorDto(
    val engine: String = "lc0",
    val error: String,
    @SerialName("request_id") val requestId: String? = null,
    val fen: String? = null,
)

@Serializable
internal data class AnalysisDoneDto(
    val message: String = "complete",
    @SerialName("request_id") val requestId: String? = null,
    val fen: String? = null,
    val cancelled: Boolean = false,
    val ply: Int? = null,
    @SerialName("final_snapshot") val finalSnapshot: Lc0SnapshotDto? = null,
)

@Serializable
internal data class LeelaValuesDto(
    val available: Boolean? = null,
    val cached: Boolean = false,
    val values: List<LeelaValuePointDto> = emptyList(),
)

@Serializable
internal data class GraphStatusDto(
    val available: Boolean = false,
    val cached: Boolean = false,
    @SerialName("settings_hash") val settingsHash: String? = null,
    val values: List<LeelaValuePointDto> = emptyList(),
    val job: GraphJobDto? = null,
)

@Serializable
internal data class GraphJobEnvelopeDto(val job: GraphJobDto)

@Serializable
internal data class GraphJobDto(
    @SerialName("job_id") val jobId: String,
    @SerialName("game_id") val gameId: String,
    val state: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val error: String? = null,
)

@Serializable
internal data class LeelaValuePointDto(
    val ply: Int,
    val value: LeelaValueDto = LeelaValueDto(),
    @SerialName("elapsed_ms") val elapsedMillis: Long? = null,
)

@Serializable
internal data class LeelaValueDto(
    val available: Boolean = false,
    val q: Double? = null,
    @SerialName("side_to_move") val sideToMove: String? = null,
    @SerialName("side_to_move_expectation") val sideToMoveExpectation: Double? = null,
    @SerialName("draw_probability") val drawProbability: Double? = null,
    @SerialName("white_wdl") val whiteWdl: WhiteWdlDto? = null,
    @SerialName("wdl_mu_pawns") val wdlMuPawns: Double? = null,
)

@Serializable
internal data class WhiteWdlDto(
    val win: Double? = null,
    val draw: Double? = null,
    val loss: Double? = null,
)

@Serializable
internal data class RevokeResponseDto(val revoked: Boolean)

@Serializable
internal data class ApiErrorDto(
    val error: String? = null,
    val detail: String? = null,
)
