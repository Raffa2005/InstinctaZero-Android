package com.instinctazero.android.data

import com.instinctazero.android.model.AnalysisEvent
import com.instinctazero.android.model.AnalysisSettings
import com.instinctazero.android.model.RepositoryFailure
import com.instinctazero.android.security.SessionCredentials
import com.instinctazero.android.security.SessionStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InstinctaRepositoryTest {
    @Test
    fun `first page filters active games caches finished games and preserves cursor`() = runTest {
        val remote = FakeRemote().apply {
            gamesResult = GamesPageDto(
                games = listOf(
                    gameSummary("new", status = "mate", createdAt = 30),
                    gameSummary("live", status = "started", createdAt = 20),
                    gameSummary("old", status = "resign", createdAt = 10),
                ),
                nextCursor = "page-2-cursor",
            )
        }
        val storage = FakeGameStorage()
        val repository = repository(remote = remote, storage = storage)

        val firstPage = repository.gamesPage(limit = 25)

        assertEquals(listOf("new", "old"), firstPage.games.map { it.id })
        assertEquals("page-2-cursor", firstPage.nextCursor)
        assertEquals(listOf("new", "old"), storage.games.map { it.id })
        assertEquals(listOf(null to 25), remote.gamePageCalls)
        assertEquals(firstPage, repository.games.value)

        remote.gamesResult = GamesPageDto(
            games = listOf(gameSummary("older", status = "draw", createdAt = 5)),
            nextCursor = null,
        )
        val secondPage = repository.gamesPage(cursor = "page-2-cursor", limit = 25)

        assertEquals(listOf("older"), secondPage.games.map { it.id })
        assertEquals(listOf(null to 25, "page-2-cursor" to 25), remote.gamePageCalls)
        assertEquals(1, storage.storeGamesCalls)
        assertEquals(firstPage, repository.games.value)
    }

    @Test
    fun `sync refresh compares the archive before refresh and identifies newest new game`() = runTest {
        val storage = FakeGameStorage().apply {
            games += gameSummary("known", status = "mate", createdAt = 10)
        }
        val remote = FakeRemote().apply {
            startSyncResult = SyncEnvelopeDto(SyncStateDto(running = true, status = "running"))
            syncResults += SyncEnvelopeDto(
                SyncStateDto(
                    running = false,
                    status = "complete",
                    gamesStored = 2,
                    lastSeenAtMillis = 300,
                ),
            )
            gamesResult = GamesPageDto(
                listOf(
                    gameSummary("newest", status = "mate", createdAt = 30),
                    gameSummary("known", status = "mate", createdAt = 10),
                ),
                nextCursor = null,
            )
        }
        val repository = repository(remote = remote, storage = storage)

        val result = repository.syncAndRefresh(limit = 50)

        assertEquals("newest", result.newestNewGameId)
        assertEquals("complete", result.sync.status)
        assertFalse(result.sync.running)
        assertEquals(1, remote.startSyncCalls)
        assertEquals(1, remote.syncStateCalls)
        assertEquals(listOf("newest", "known"), repository.games.value.games.map { it.id })
        assertNull(repository.lastError.value)
    }

    @Test
    fun `sync failure is surfaced but does not discard a successful game refresh`() = runTest {
        val failure = RepositoryFailure.Unavailable("sync offline")
        val remote = FakeRemote().apply {
            startSyncFailure = failure
            gamesResult = GamesPageDto(
                listOf(gameSummary("completed", status = "mate", createdAt = 10)),
                null,
            )
        }
        val repository = repository(remote = remote)

        val result = repository.syncAndRefresh()

        assertEquals(listOf("completed"), result.page.games.map { it.id })
        assertSame(failure, repository.lastError.value)
    }

    @Test
    fun `completed game detail is served from cache without a network request`() = runTest {
        val cached = gameDetail("cached", status = "mate")
        val storage = FakeGameStorage().apply { details[cached.id] = cached }
        val remote = FakeRemote()
        val repository = repository(remote = remote, storage = storage)

        val game = repository.game("cached")

        assertEquals("cached", game.summary.id)
        assertEquals(0, remote.gameCalls)
    }

    @Test
    fun `ongoing cached game cannot reach analysis data source`() = runTest {
        val active = gameDetail("active", status = "started")
        val storage = FakeGameStorage().apply { details[active.id] = active }
        val analysis = FakeAnalysisDataSource()
        val repository = repository(storage = storage, analysis = analysis)

        val failure = expectRepositoryFailure {
            repository.analysis("active", ply = 2).toList()
        }

        assertTrue(failure is RepositoryFailure.InvalidResponse)
        assertEquals(0, analysis.observeCalls)
    }

    @Test
    fun `active game cannot start a graph job`() = runTest {
        val active = gameDetail("active", status = "started")
        val storage = FakeGameStorage().apply { details[active.id] = active }
        val remote = FakeRemote()
        val repository = repository(remote = remote, storage = storage)

        val failure = expectRepositoryFailure { repository.startGraphJob("active") }

        assertTrue(failure is RepositoryFailure.InvalidResponse)
        assertEquals(0, remote.startGraphCalls)
    }

    @Test
    fun `graph cleanup status never falls back to cached values`() = runTest {
        val finished = gameDetail("finished", status = "mate")
        val storage = FakeGameStorage().apply {
            details[finished.id] = finished
            values[finished.id] = LeelaValuesDto(available = true)
        }
        val offline = RepositoryFailure.Unavailable("PC offline")
        val remote = FakeRemote().apply { graphStatusFailure = offline }
        val repository = repository(remote = remote, storage = storage)

        val normalStatus = repository.graphJobStatus("finished")
        assertTrue(normalStatus.cached)

        val failure = expectRepositoryFailure { repository.graphJobStatusForCleanup("finished") }
        assertSame(offline, failure)
        assertEquals(2, remote.graphStatusCalls)
    }

    @Test
    fun `settings storage round trips exact backend and custom limits`() {
        val settings = FakeSettingsStorage()
        val repository = repository(settings = settings)

        assertEquals(AnalysisSettings(), repository.analysisSettings())

        val custom = AnalysisSettings(nodes = 4_096, multipv = 3, profile = "experimental-fast")
        repository.saveAnalysisSettings(custom)

        assertEquals(custom, repository.analysisSettings())
        assertEquals(custom, settings.saved)
        assertNotNull(settings.saved)
    }

    @Test
    fun `disconnect clears local pairing before best effort revoke`() = runTest {
        val store = FakeSessionStorage().apply { save("https://pc.example", "token", "device") }
        val remote = FakeRemote()
        remote.onRevoke = { assertNull(store.load()) }
        val storage = FakeGameStorage().apply { games += gameSummary("cached", "mate", 1) }
        val repository = InstinctaRepository(store, remote, FakeAnalysisDataSource(), storage, FakeSettingsStorage())

        repository.disconnect()

        assertNull(store.load())
        assertTrue(repository.pairingState.value is com.instinctazero.android.model.PairingState.Unpaired)
        assertTrue(repository.games.value.games.isEmpty())
    }

    private fun repository(
        remote: FakeRemote = FakeRemote(),
        storage: FakeGameStorage = FakeGameStorage(),
        analysis: FakeAnalysisDataSource = FakeAnalysisDataSource(),
        settings: FakeSettingsStorage = FakeSettingsStorage(),
    ) = InstinctaRepository(
        sessionStore = FakeSessionStorage(),
        api = remote,
        analysisClient = analysis,
        cache = storage,
        settingsStore = settings,
    )

    private suspend fun expectRepositoryFailure(block: suspend () -> Unit): RepositoryFailure {
        try {
            block()
            fail("Expected RepositoryFailure")
        } catch (failure: RepositoryFailure) {
            return failure
        }
        error("unreachable")
    }

    private fun gameSummary(
        id: String,
        status: String,
        createdAt: Long,
    ) = GameSummaryDto(
        id = id,
        createdAtMillis = createdAt,
        lastMoveAtMillis = createdAt + 1,
        status = status,
        rated = true,
        speed = "rapid",
        perf = "rapid",
        variant = "standard",
        white = PlayerDto("White", 2100),
        black = PlayerDto("Black", 2050),
        result = if (status == "started") "*" else "1-0",
        plyCount = 2,
    )

    private fun gameDetail(id: String, status: String) = GameDetailDto(
        id = id,
        createdAtMillis = 10,
        lastMoveAtMillis = 20,
        status = status,
        rated = true,
        speed = "rapid",
        perf = "rapid",
        variant = "standard",
        white = PlayerDto("White"),
        black = PlayerDto("Black"),
        result = if (status == "started") "*" else "1-0",
        plyCount = 1,
        pgn = "1. e4",
        initialFen = "8/8/8/8/8/8/8/8 w - - 0 1",
        moves = listOf(
            GameMoveDto(1, "e2e4", "e4", "8/8/8/8/4P3/8/8/8 b - - 0 1"),
        ),
    )

    private class FakeSessionStorage : SessionStorage {
        private var credentials: SessionCredentials? = null

        override fun load(): SessionCredentials? = credentials

        override fun save(baseUrl: String, bearerToken: String, deviceId: String) {
            credentials = SessionCredentials(baseUrl, bearerToken, deviceId)
        }

        override fun clear() {
            credentials = null
        }
    }

    private class FakeRemote : RemoteDataSource {
        var gamesResult = GamesPageDto()
        var startSyncResult = SyncEnvelopeDto(SyncStateDto(running = false, status = "complete"))
        var startSyncFailure: RepositoryFailure? = null
        val syncResults = ArrayDeque<SyncEnvelopeDto>()
        val gamePageCalls = mutableListOf<Pair<String?, Int>>()
        var startSyncCalls = 0
        var syncStateCalls = 0
        var gameCalls = 0
        var startGraphCalls = 0
        var graphStatusCalls = 0
        var revokeCalls = 0
        var onRevoke: (() -> Unit)? = null
        var graphStatusFailure: RepositoryFailure? = null

        override suspend fun claimPairing(
            baseUrl: String,
            code: String,
            deviceName: String,
        ): PairClaimResponseDto = unsupported()

        override suspend fun session(): SessionDto = unsupported()

        override suspend fun startSync(): SyncEnvelopeDto {
            startSyncCalls++
            startSyncFailure?.let { throw it }
            return startSyncResult
        }

        override suspend fun syncState(): SyncEnvelopeDto {
            syncStateCalls++
            return syncResults.removeFirstOrNull() ?: startSyncResult
        }

        override suspend fun games(cursor: String?, limit: Int): GamesPageDto {
            gamePageCalls += cursor to limit
            return gamesResult
        }

        override suspend fun game(gameId: String): GameEnvelopeDto {
            gameCalls++
            return unsupported()
        }

        override suspend fun explorer(
            gameId: String,
            ply: Int,
            source: String,
        ): ExplorerEnvelopeDto = unsupported()

        override suspend fun values(gameId: String, calculate: Boolean): LeelaValuesDto = unsupported()

        override suspend fun graphStatus(gameId: String): GraphStatusDto {
            graphStatusCalls++
            graphStatusFailure?.let { throw it }
            return GraphStatusDto()
        }

        override suspend fun startGraph(gameId: String): GraphStatusDto {
            startGraphCalls++
            return GraphStatusDto()
        }

        override suspend fun revokeSession(): RevokeResponseDto {
            revokeCalls++
            onRevoke?.invoke()
            return RevokeResponseDto(true)
        }

        private fun <T> unsupported(): T = error("Unexpected fake remote call")
    }

    private class FakeAnalysisDataSource : AnalysisDataSource {
        var observeCalls = 0

        override fun observe(
            gameId: String,
            ply: Int,
            settings: AnalysisSettings,
        ): Flow<AnalysisEvent> {
            observeCalls++
            return flowOf(AnalysisEvent.Completed("request", "fen", false, "complete"))
        }
    }

    private class FakeGameStorage : GameStorage {
        val games = mutableListOf<GameSummaryDto>()
        val details = mutableMapOf<String, GameDetailDto>()
        val values = mutableMapOf<String, LeelaValuesDto>()
        var storeGamesCalls = 0

        override fun storeGames(games: List<GameSummaryDto>) {
            storeGamesCalls++
            games.forEach { incoming ->
                this.games.removeAll { it.id == incoming.id }
                this.games += incoming
            }
            this.games.sortWith(compareByDescending<GameSummaryDto> { it.createdAtMillis }.thenByDescending { it.id })
        }

        override fun loadGames(limit: Int): List<GameSummaryDto> = games.take(limit)

        override fun storeGame(game: GameDetailDto) {
            details[game.id] = game
        }

        override fun loadGame(gameId: String): GameDetailDto? = details[gameId]

        override fun storeValues(gameId: String, values: LeelaValuesDto) {
            this.values[gameId] = values
        }

        override fun loadValues(gameId: String): LeelaValuesDto? = values[gameId]

        override fun clear() {
            games.clear()
            details.clear()
            values.clear()
        }
    }

    private class FakeSettingsStorage : SettingsStorage {
        var saved: AnalysisSettings? = null

        override fun load(): AnalysisSettings = saved ?: AnalysisSettings()

        override fun save(settings: AnalysisSettings) {
            saved = settings
        }
    }
}
