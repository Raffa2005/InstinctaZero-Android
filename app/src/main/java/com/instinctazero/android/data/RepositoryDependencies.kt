package com.instinctazero.android.data

import com.instinctazero.android.model.AnalysisEvent
import com.instinctazero.android.model.AnalysisSettings
import com.instinctazero.android.security.SessionCredentials
import kotlinx.coroutines.flow.Flow

internal interface RemoteDataSource {
    suspend fun claimPairing(baseUrl: String, code: String, deviceName: String): PairClaimResponseDto
    suspend fun session(): SessionDto
    suspend fun startSync(): SyncEnvelopeDto
    suspend fun syncState(): SyncEnvelopeDto
    suspend fun games(cursor: String?, limit: Int): GamesPageDto
    suspend fun game(gameId: String): GameEnvelopeDto
    suspend fun explorer(gameId: String, ply: Int, source: String): ExplorerEnvelopeDto
    suspend fun values(gameId: String, calculate: Boolean): LeelaValuesDto
    suspend fun graphStatus(gameId: String): GraphStatusDto = error("Graph status is unsupported.")
    suspend fun startGraph(gameId: String): GraphStatusDto = error("Graph jobs are unsupported.")
    suspend fun cancelGraph(gameId: String, jobId: String): GraphJobEnvelopeDto =
        error("Graph cancellation is unsupported.")
    suspend fun revokeSession(): RevokeResponseDto
    /** Allows local credentials to be cleared before the best-effort network revoke is sent. */
    suspend fun revokeSession(credentials: SessionCredentials?): RevokeResponseDto = revokeSession()
}

internal interface AnalysisDataSource {
    fun observe(gameId: String, ply: Int, settings: AnalysisSettings): Flow<AnalysisEvent>
}

internal interface GameStorage {
    fun storeGames(games: List<GameSummaryDto>)
    fun loadGames(limit: Int = 50): List<GameSummaryDto>
    fun storeGame(game: GameDetailDto)
    fun loadGame(gameId: String): GameDetailDto?
    fun storeValues(gameId: String, values: LeelaValuesDto)
    fun loadValues(gameId: String): LeelaValuesDto?
    fun clear()
}

internal interface SettingsStorage {
    fun load(): AnalysisSettings
    fun save(settings: AnalysisSettings)
}
