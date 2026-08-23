package com.instinctazero.android.data

import android.content.Context
import com.instinctazero.android.model.AnalysisEvent
import com.instinctazero.android.model.AnalysisSettings
import com.instinctazero.android.model.ExplorerResult
import com.instinctazero.android.model.GameDetail
import com.instinctazero.android.model.GamesPage
import com.instinctazero.android.model.GraphJob
import com.instinctazero.android.model.GraphStatus
import com.instinctazero.android.model.LeelaValues
import com.instinctazero.android.model.PairingResult
import com.instinctazero.android.model.PairingState
import com.instinctazero.android.model.RefreshResult
import com.instinctazero.android.model.RepositoryFailure
import com.instinctazero.android.model.SessionInfo
import com.instinctazero.android.model.SyncState
import com.instinctazero.android.security.SecureSessionStore
import com.instinctazero.android.security.SessionStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.LinkedHashMap

/** Single data-layer entry point intended for an application-scoped ViewModel. */
class InstinctaRepository internal constructor(
    private val sessionStore: SessionStorage,
    private val api: RemoteDataSource,
    private val analysisClient: AnalysisDataSource,
    private val cache: GameStorage,
    private val settingsStore: SettingsStorage,
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val _pairingState = MutableStateFlow<PairingState>(
        sessionStore.load()?.let { PairingState.Paired(it.deviceId) } ?: PairingState.Unpaired,
    )
    private val _games = MutableStateFlow(GamesPage(emptyList(), null))
    private val _syncState = MutableStateFlow(SyncState())
    private val _lastError = MutableStateFlow<RepositoryFailure?>(null)
    private val explorerCache = object : LinkedHashMap<ExplorerKey, CachedExplorer>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ExplorerKey, CachedExplorer>): Boolean =
            size > 64
    }

    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()
    val games: StateFlow<GamesPage> = _games.asStateFlow()
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    val lastError: StateFlow<RepositoryFailure?> = _lastError.asStateFlow()

    suspend fun claimPairing(
        serverBaseUrl: String,
        pairingCode: String,
        deviceName: String,
    ): PairingResult {
        require(pairingCode.isNotBlank()) { "Pairing code is required." }
        require(deviceName.isNotBlank()) { "Device name is required." }
        val result = api.claimPairing(serverBaseUrl, pairingCode, deviceName).toModel()
        sessionStore.save(serverBaseUrl, result.token, result.device.id)
        _pairingState.value = PairingState.Paired(result.device.id)
        return result
    }

    suspend fun loadSession(): SessionInfo {
        val session = api.session().toModel()
        _syncState.value = session.sync
        _pairingState.value = PairingState.Ready(session)
        return session
    }

    /** Emits disk content immediately when the first ViewModel is created. */
    suspend fun loadCachedGames(limit: Int = 50): GamesPage = withContext(Dispatchers.IO) {
        val page = GamesPage(cache.loadGames(limit).map { it.toModel() }, null)
        _games.value = page
        page
    }

    suspend fun gamesPage(cursor: String? = null, limit: Int = 50): GamesPage {
        val remote = api.games(cursor, limit)
        val finished = remote.games.filter { it.status.lowercase() !in ACTIVE_GAME_STATUSES }
        if (cursor == null) {
            withContext(Dispatchers.IO) { cache.storeGames(finished) }
        }
        val page = GamesPage(finished.map { it.toModel() }, remote.nextCursor)
        if (cursor == null) _games.value = page
        return page
    }

    /**
     * Starts the PC's incremental archive sync, waits briefly for coalesced work to finish,
     * then refreshes the newest page. Existing StateFlows are deliberately left intact on error.
     */
    suspend fun syncAndRefresh(limit: Int = 50): RefreshResult = syncMutex.withLock {
        val knownIds = withContext(Dispatchers.IO) { cache.loadGames(250).mapTo(mutableSetOf()) { it.id } }
        var state = _syncState.value
        var syncFailure: RepositoryFailure? = null
        try {
            state = api.startSync().sync.toModel()
            _syncState.value = state
            var polls = 0
            while (state.running && polls < SYNC_POLL_ATTEMPTS) {
                delay(SYNC_POLL_MILLIS)
                state = api.syncState().sync.toModel()
                _syncState.value = state
                polls += 1
            }
        } catch (failure: RepositoryFailure) {
            syncFailure = failure
            _lastError.value = failure
        }
        val page = try {
            gamesPage(limit = limit)
        } catch (failure: RepositoryFailure) {
            _lastError.value = failure
            throw failure
        }
        if (syncFailure == null) _lastError.value = null
        val newestNew = page.games.firstOrNull { it.id !in knownIds }?.id
        RefreshResult(page, state, newestNew)
    }

    suspend fun game(gameId: String): GameDetail {
        withContext(Dispatchers.IO) { cache.loadGame(gameId) }?.let {
            val cached = it.toModel()
            requireFinished(cached)
            return cached
        }
        val dto = api.game(gameId).game
        val game = dto.toModel()
        requireFinished(game)
        withContext(Dispatchers.IO) { cache.storeGame(dto) }
        return game
    }

    suspend fun explorer(gameId: String, ply: Int, source: String): ExplorerResult {
        requireFinished(game(gameId))
        val key = ExplorerKey(gameId, ply, source)
        synchronized(explorerCache) {
            explorerCache[key]?.takeIf { System.currentTimeMillis() - it.savedAt < EXPLORER_TTL_MILLIS }
                ?.let { return it.value }
        }
        val value = api.explorer(gameId, ply, source).toModel()
        synchronized(explorerCache) { explorerCache[key] = CachedExplorer(value, System.currentTimeMillis()) }
        return value
    }

    fun analysis(
        gameId: String,
        ply: Int,
        settings: AnalysisSettings = analysisSettings(),
    ): Flow<AnalysisEvent> {
        require(gameId.isNotBlank()) { "A stored game id is required." }
        return flow {
            requireFinished(game(gameId))
            emitAll(analysisClient.observe(gameId, ply, settings))
        }
    }

    suspend fun leelaValues(gameId: String, calculate: Boolean = false): LeelaValues {
        requireFinished(game(gameId))
        val cached = withContext(Dispatchers.IO) { cache.loadValues(gameId) }
        if (!calculate && cached != null) return cached.toModel()
        return try {
            val status = if (calculate) startGraphJob(gameId) else graphJobStatus(gameId)
            _lastError.value = null
            status.values ?: cached?.toModel() ?: LeelaValues(false, false, emptyList())
        } catch (failure: RepositoryFailure) {
            _lastError.value = failure
            cached?.toModel() ?: throw failure
        }
    }

    suspend fun startGraphJob(gameId: String): GraphStatus {
        requireFinished(game(gameId))
        return persistGraphStatus(gameId, api.startGraph(gameId))
    }

    suspend fun graphJobStatus(gameId: String): GraphStatus {
        requireFinished(game(gameId))
        return try {
            persistGraphStatus(gameId, api.graphStatus(gameId))
        } catch (failure: RepositoryFailure) {
            _lastError.value = failure
            val cached = withContext(Dispatchers.IO) { cache.loadValues(gameId) }
                ?: throw failure
            GraphStatus(true, true, null, cached.toModel(), null)
        }
    }

    /** Cleanup path: never substitute cached values for a remote job lookup. */
    suspend fun graphJobStatusForCleanup(gameId: String): GraphStatus {
        requireFinished(game(gameId))
        return persistGraphStatus(gameId, api.graphStatus(gameId))
    }

    suspend fun cancelGraphJob(gameId: String, jobId: String): GraphJob {
        require(jobId.isNotBlank()) { "A graph job id is required." }
        requireFinished(game(gameId))
        val job = api.cancelGraph(gameId, jobId).job.toModel()
        if (job.gameId != gameId || job.jobId != jobId) {
            throw RepositoryFailure.InvalidResponse("The PC returned another graph job.")
        }
        return job
    }

    private suspend fun persistGraphStatus(gameId: String, dto: GraphStatusDto): GraphStatus {
        if (dto.job != null && dto.job.gameId != gameId) {
            throw RepositoryFailure.InvalidResponse("The PC returned a graph job for another game.")
        }
        if (dto.values.isNotEmpty()) {
            withContext(Dispatchers.IO) { cache.storeValues(gameId, dto.valuesDto()) }
        }
        return dto.toModel()
    }

    suspend fun cachedLeelaValues(gameId: String): LeelaValues? = withContext(Dispatchers.IO) {
        cache.loadValues(gameId)?.toModel()
    }

    fun analysisSettings(): AnalysisSettings = settingsStore.load()

    fun saveAnalysisSettings(settings: AnalysisSettings) = settingsStore.save(settings)

    suspend fun disconnect() {
        // Re-pairing must be possible immediately, including when the old PC is offline.
        val credentials = sessionStore.load()
        sessionStore.clear()
        _pairingState.value = PairingState.Unpaired
        withContext(Dispatchers.IO) { cache.clear() }
        synchronized(explorerCache) { explorerCache.clear() }
        _games.value = GamesPage(emptyList(), null)
        _syncState.value = SyncState()
        // Remote cleanup is detached: an offline PC must never hold the pairing UI hostage.
        backgroundScope.launch { runCatching { api.revokeSession(credentials) } }
    }

    private fun requireFinished(game: GameDetail) {
        if (!game.summary.isFinished) {
            throw RepositoryFailure.InvalidResponse("Analysis is unavailable until this game has finished.")
        }
    }

    companion object {
        private const val SYNC_POLL_ATTEMPTS = 60
        private const val SYNC_POLL_MILLIS = 500L
        private const val EXPLORER_TTL_MILLIS = 5 * 60 * 1_000L
        private val ACTIVE_GAME_STATUSES = setOf("created", "started")

        fun create(context: Context, httpClient: OkHttpClient = OkHttpClient()): InstinctaRepository {
            val appContext = context.applicationContext
            val store = SecureSessionStore(appContext)
            val api = MobileApiClient(store, baseClient = httpClient)
            return InstinctaRepository(
                sessionStore = store,
                api = api,
                analysisClient = AnalysisStreamClient(api),
                cache = GameCache(appContext),
                settingsStore = AnalysisSettingsStore(appContext),
            )
        }
    }

    private data class ExplorerKey(val gameId: String, val ply: Int, val source: String)
    private data class CachedExplorer(val value: ExplorerResult, val savedAt: Long)
}
