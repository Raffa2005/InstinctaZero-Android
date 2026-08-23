package com.instinctazero.android

import android.app.Application
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instinctazero.android.data.DemoFixtures
import com.instinctazero.android.data.InstinctaRepository
import com.instinctazero.android.model.AnalysisEvent
import com.instinctazero.android.model.AnalysisSettings
import com.instinctazero.android.model.ExplorerResult
import com.instinctazero.android.model.GameDetail
import com.instinctazero.android.model.GameSummary
import com.instinctazero.android.model.GraphJob
import com.instinctazero.android.model.GraphJobState
import com.instinctazero.android.model.GraphStatus
import com.instinctazero.android.model.LeelaValues
import com.instinctazero.android.model.PairingState
import com.instinctazero.android.model.RepositoryFailure
import com.instinctazero.android.model.SearchSnapshot
import com.instinctazero.android.ui.AnalysisAction
import com.instinctazero.android.ui.AnalysisProfileUi
import com.instinctazero.android.ui.AnalysisTab
import com.instinctazero.android.ui.AnalysisUiState
import com.instinctazero.android.ui.AppAction
import com.instinctazero.android.ui.AppScreen
import com.instinctazero.android.ui.AppUiState
import com.instinctazero.android.ui.BoardArrow
import com.instinctazero.android.ui.BookMove
import com.instinctazero.android.ui.EngineLine
import com.instinctazero.android.ui.EvaluationPoint
import com.instinctazero.android.ui.GameSummaryUi
import com.instinctazero.android.ui.MovePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class InstinctaViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as InstinctaZeroApplication
    private val repository = app.repository
    private val preferences = app.preferences
    private val storedPreferences = preferences.load()
    private val storedAnalysis = repository.analysisSettings()
    private val _uiState = MutableStateFlow(
        AppUiState(
            screen = if (repository.pairingState.value is PairingState.Unpaired) AppScreen.PAIRING else AppScreen.GAMES,
            accountName = storedPreferences.accountName,
            hasPairing = repository.pairingState.value !is PairingState.Unpaired,
            autoFetch = storedPreferences.autoFetch,
            autoOpenNewest = storedPreferences.autoOpenNewest,
            showArrows = storedPreferences.showArrows,
            showOpeningBook = storedPreferences.showOpeningBook,
            selectedProfileId = storedAnalysis.profile,
            targetNodes = storedAnalysis.nodes,
            multiPv = storedAnalysis.multipv,
        ),
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var currentGame: GameDetail? = null
    private var currentPly = 0
    private var positionJob: Job? = null
    private var positionGeneration = 0L
    private var graphPollJob: Job? = null
    private var activeGraphGameId: String? = null
    private var activeGraphJobId: String? = null
    private var graphGeneration = 0L
    private var graphStopRequested = false
    private var openGameJob: Job? = null
    private var engineEnabled = true
    private var foregroundActive = false
    private var refreshJob: Job? = null
    private var settingsReturnScreen = AppScreen.GAMES
    private var lastForegroundRefreshAt = 0L

    init {
        viewModelScope.launch {
            val cached = repository.loadCachedGames()
            updateGames(cached.games)
            if (repository.pairingState.value !is PairingState.Unpaired) {
                connectSession()
                if (_uiState.value.autoFetch) refreshGames()
            }
        }
    }

    fun onForeground() {
        foregroundActive = true
        if (
            engineEnabled
            && _uiState.value.screen == AppScreen.ANALYSIS
            && currentGame?.summary?.id != DemoFixtures.game.summary.id
        ) {
            startCurrentWork()
        }
        if (!_uiState.value.autoFetch || repository.pairingState.value is PairingState.Unpaired) return
        val now = System.currentTimeMillis()
        if (now - lastForegroundRefreshAt < FOREGROUND_REFRESH_DEBOUNCE_MILLIS) return
        lastForegroundRefreshAt = now
        refreshGames()
    }

    fun onBackground() {
        foregroundActive = false
        positionJob?.cancel()
        stopGraphWork(cancelRemote = true)
        openGameJob?.cancel()
        _uiState.value = _uiState.value.copy(busy = false)
        if (_uiState.value.screen == AppScreen.ANALYSIS) {
            updateAnalysis { it.copy(engineThinking = false) }
        }
    }

    fun onAction(action: AppAction) {
        when (action) {
            AppAction.Back -> {
                val destination = if (_uiState.value.screen == AppScreen.SETTINGS) settingsReturnScreen else AppScreen.GAMES
                if (_uiState.value.screen == AppScreen.ANALYSIS || destination != AppScreen.ANALYSIS) {
                    cancelPositionWork()
                    stopGraphWork(cancelRemote = true)
                }
                _uiState.value = _uiState.value.copy(screen = destination, error = null)
                if (destination == AppScreen.ANALYSIS) startCurrentWork()
            }
            AppAction.OpenDemo -> openDemo()
            is AppAction.ClaimPairing -> claimPairing(action.url, action.code, action.deviceName)
            AppAction.RefreshGames -> refreshGames()
            AppAction.OpenSettings -> {
                settingsReturnScreen = _uiState.value.screen
                if (_uiState.value.screen == AppScreen.ANALYSIS) {
                    cancelPositionWork()
                    stopGraphWork(cancelRemote = true)
                }
                _uiState.value = _uiState.value.copy(screen = AppScreen.SETTINGS, error = null)
            }
            AppAction.OpenGames -> {
                cancelPositionWork()
                stopGraphWork(cancelRemote = true)
                _uiState.value = _uiState.value.copy(screen = AppScreen.GAMES, error = null)
            }
            AppAction.DisconnectPc -> disconnect()
            is AppAction.OpenGame -> openGame(action.id)
            is AppAction.SetAutoFetch -> updatePreferences(autoFetch = action.enabled)
            is AppAction.SetAutoOpenNewest -> updatePreferences(autoOpenNewest = action.enabled)
            is AppAction.SetShowArrows -> {
                updatePreferences(showArrows = action.enabled)
                if (!action.enabled) updateAnalysis { it.copy(arrows = emptyList()) } else startCurrentWork()
            }
            is AppAction.SetShowOpeningBook -> {
                updatePreferences(showOpeningBook = action.enabled)
                if (!action.enabled) updateAnalysis { it.copy(bookMoves = emptyList()) } else loadExplorer()
            }
            is AppAction.SetAnalysisProfile -> saveAnalysisSettings(profile = action.id)
            is AppAction.SetTargetNodes -> saveAnalysisSettings(nodes = action.nodes)
            is AppAction.SetMultiPv -> saveAnalysisSettings(multipv = action.lines)
            is AppAction.Analysis -> handleAnalysisAction(action.action)
        }
    }

    private suspend fun connectSession() {
        try {
            val session = repository.loadSession()
            val profiles = session.capabilities.profiles.ifEmpty { listOf(EXACT_PROFILE) }
            val selected = _uiState.value.selectedProfileId.takeIf(profiles::contains) ?: EXACT_PROFILE
            val settings = AnalysisSettings(
                nodes = _uiState.value.targetNodes.coerceIn(1, session.capabilities.maxNodes),
                multipv = _uiState.value.multiPv.coerceIn(1, session.capabilities.maxMultipv),
                profile = selected,
            )
            repository.saveAnalysisSettings(settings)
            _uiState.value = _uiState.value.copy(
                accountName = session.account.username,
                hasPairing = true,
                pcConnected = true,
                error = session.sync.lastError,
                availableProfiles = profiles.map(::profileUi),
                selectedProfileId = settings.profile,
                targetNodes = settings.nodes,
                multiPv = settings.multipv,
            )
            savePreferenceState(accountName = session.account.username)
            updateGames(repository.games.value.games)
        } catch (failure: Exception) {
            handleConnectionFailure(failure)
        }
    }

    private fun claimPairing(url: String, code: String, deviceName: String) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, error = null)
            try {
                val name = deviceName.ifBlank { Build.MODEL.ifBlank { "Android phone" } }
                repository.claimPairing(url, code, name)
                _uiState.value = _uiState.value.copy(hasPairing = true)
                connectSession()
                _uiState.value = _uiState.value.copy(screen = AppScreen.GAMES, busy = false)
                refreshGames()
            } catch (failure: Exception) {
                _uiState.value = _uiState.value.copy(busy = false, error = friendlyMessage(failure))
            }
        }
    }

    private fun refreshGames() {
        if (refreshJob?.isActive == true || repository.pairingState.value is PairingState.Unpaired) return
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshingGames = true, error = null)
            try {
                val result = repository.syncAndRefresh()
                updateGames(result.page.games)
                _uiState.value = _uiState.value.copy(
                    isRefreshingGames = result.sync.running,
                    pcConnected = true,
                    error = result.sync.lastError,
                )
                if (_uiState.value.autoOpenNewest) result.newestNewGameId?.let(::openGame)
            } catch (failure: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshingGames = false,
                    pcConnected = false,
                    error = friendlyMessage(failure),
                )
                if (failure is RepositoryFailure.Unauthorized) {
                    repository.disconnect()
                    _uiState.value = _uiState.value.copy(screen = AppScreen.PAIRING)
                }
            }
        }
    }

    private fun updateGames(games: List<GameSummary>) {
        val account = _uiState.value.accountName
        _uiState.value = _uiState.value.copy(games = games.map { it.toUi(account) })
    }

    private fun openGame(gameId: String) {
        cancelPositionWork()
        stopGraphWork(cancelRemote = true)
        openGameJob?.cancel()
        openGameJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, error = null)
            try {
                val game = repository.game(gameId)
                if (game.summary.id != gameId) return@launch
                currentGame = game
                currentPly = game.moves.size
                engineEnabled = true
                _uiState.value = _uiState.value.copy(
                    screen = AppScreen.ANALYSIS,
                    busy = false,
                    analysis = analysisStateFor(game, currentPly),
                )
                startCurrentWork()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                _uiState.value = _uiState.value.copy(busy = false, error = friendlyMessage(failure))
            }
        }
    }

    private fun openDemo() {
        positionJob?.cancel()
        stopGraphWork(cancelRemote = true)
        openGameJob?.cancel()
        engineEnabled = false
        currentGame = DemoFixtures.game
        currentPly = DemoFixtures.game.moves.size
        val base = analysisStateFor(DemoFixtures.game, currentPly).withSnapshot(DemoFixtures.search, true)
        _uiState.value = _uiState.value.copy(
            screen = AppScreen.ANALYSIS,
            accountName = "Demo",
            pcConnected = false,
            error = null,
            analysis = base.copy(
                message = "Offline demo · no PC connection",
                bookMoves = DemoFixtures.explorer.toBookMoves(),
                evaluations = DemoFixtures.values.toEvaluationPoints(),
                engineThinking = false,
            ),
        )
    }

    private fun selectPly(ply: Int) {
        val game = currentGame ?: return
        currentPly = ply.coerceIn(0, game.moves.size)
        _uiState.value = _uiState.value.copy(analysis = analysisStateFor(game, currentPly, _uiState.value.analysis))
        if (game.summary.id != DemoFixtures.game.summary.id && engineEnabled) {
            if (_uiState.value.analysis.selectedTab != AnalysisTab.GRAPH) startPositionWork()
        }
    }

    private fun startPositionWork() {
        if (
            !engineEnabled ||
            !foregroundActive ||
            _uiState.value.screen != AppScreen.ANALYSIS ||
            _uiState.value.analysis.selectedTab == AnalysisTab.GRAPH
        ) return
        val game = currentGame ?: return
        val gameId = game.summary.id
        val ply = currentPly
        positionJob?.cancel()
        val generation = ++positionGeneration
        updateAnalysis {
            it.copy(
                engineThinking = true,
                message = null,
            )
        }
        positionJob = viewModelScope.launch {
            val sideJobs = listOf(
                async { if (_uiState.value.showOpeningBook) loadExplorer(gameId, ply) },
                async { loadValues(gameId) },
            )
            try {
                repository.analysis(gameId, ply, currentSettings()).collect { event ->
                    if (!isCurrentPositionWork(generation, gameId, ply)) return@collect
                    when (event) {
                        is AnalysisEvent.Started -> updateAnalysis { it.copy(engineThinking = true, message = null) }
                        is AnalysisEvent.Snapshot -> applySnapshot(event.value)
                        is AnalysisEvent.Failed -> updateAnalysis { it.copy(message = event.message) }
                        is AnalysisEvent.Completed -> {
                            event.finalSnapshot?.let(::applySnapshot)
                            updateAnalysis { it.copy(engineThinking = false, message = null) }
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (isCurrentPositionWork(generation, gameId, ply)) {
                    updateAnalysis { it.copy(engineThinking = false, message = friendlyMessage(failure)) }
                    _uiState.value = _uiState.value.copy(pcConnected = false)
                }
            } finally {
                sideJobs.forEach { it.cancel() }
            }
        }
    }

    private fun isCurrentPositionWork(generation: Long, gameId: String, ply: Int): Boolean =
        positionGeneration == generation && foregroundActive &&
            _uiState.value.screen == AppScreen.ANALYSIS &&
            _uiState.value.analysis.selectedTab != AnalysisTab.GRAPH &&
            currentGame?.summary?.id == gameId && currentPly == ply

    private fun restartAnalysis() {
        if (
            engineEnabled
            && foregroundActive
            && _uiState.value.screen == AppScreen.ANALYSIS
            && _uiState.value.analysis.selectedTab != AnalysisTab.GRAPH
            && currentGame?.summary?.id != DemoFixtures.game.summary.id
        ) {
            startPositionWork()
        }
    }

    private fun startCurrentWork() {
        if (_uiState.value.analysis.selectedTab == AnalysisTab.GRAPH) {
            startOrResumeGraph()
        } else {
            restartAnalysis()
        }
    }

    /** Graph generation is deliberately separate from the position stream: it is PC-wide work. */
    private fun startOrResumeGraph() {
        val game = currentGame ?: return
        if (
            !engineEnabled || !foregroundActive || _uiState.value.screen != AppScreen.ANALYSIS ||
            _uiState.value.analysis.selectedTab != AnalysisTab.GRAPH ||
            game.summary.id == DemoFixtures.game.summary.id
        ) return
        val gameId = game.summary.id
        if (graphPollJob?.isActive == true && activeGraphGameId == gameId) return
        cancelPositionWork()
        graphStopRequested = false
        val generation = ++graphGeneration
        // Record pending ownership before POST: the PC can accept it even if this coroutine is
        // cancelled before the response containing its job id arrives.
        activeGraphGameId = gameId
        activeGraphJobId = null
        graphPollJob = viewModelScope.launch {
            try {
                // POST is coalesced by the PC. Its returned job is the one this ViewModel owns.
                var status = try {
                    repository.startGraphJob(gameId)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    // A response can be lost after the PC accepted the POST; recover that job by GET.
                    repository.graphJobStatus(gameId)
                }
                var cancellationRestartAttempts = 0
                while (isCurrentGraph(generation, gameId)) {
                    val job = status.job
                    status.values?.takeIf { it.values.isNotEmpty() }?.let { values ->
                        updateAnalysis { it.copy(evaluations = values.toEvaluationPoints()) }
                    }
                    if (job == null || job.isTerminal) {
                        if (job?.state == GraphJobState.CANCELLED && cancellationRestartAttempts < 1 && isCurrentGraph(generation, gameId)) {
                            cancellationRestartAttempts += 1
                            status = repository.startGraphJob(gameId)
                            continue
                        }
                        if (job?.state == GraphJobState.ERROR) {
                            updateAnalysis { it.copy(message = job.error ?: "Graph analysis failed on PC") }
                        } else {
                            updateAnalysis { it.copy(message = null) }
                        }
                        clearGraphOwnership(gameId, job?.jobId)
                        return@launch
                    }
                    activeGraphGameId = gameId
                    activeGraphJobId = job.jobId
                    updateAnalysis { it.copy(message = graphProgressMessage(job.state)) }
                    delay(GRAPH_POLL_MILLIS)
                    if (!isCurrentGraph(generation, gameId)) return@launch
                    status = repository.graphJobStatus(gameId)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (isCurrentGraph(generation, gameId)) {
                    updateAnalysis { it.copy(message = friendlyMessage(failure)) }
                    _uiState.value = _uiState.value.copy(pcConnected = false)
                    clearGraphOwnership(gameId, activeGraphJobId)
                }
            }
        }
    }

    private fun isCurrentGraph(generation: Long, gameId: String): Boolean =
        !graphStopRequested && graphGeneration == generation && foregroundActive &&
            _uiState.value.screen == AppScreen.ANALYSIS &&
            _uiState.value.analysis.selectedTab == AnalysisTab.GRAPH &&
            currentGame?.summary?.id == gameId

    private fun clearGraphOwnership(gameId: String, jobId: String?) {
        if (activeGraphGameId == gameId && (jobId == null || activeGraphJobId == jobId)) {
            activeGraphGameId = null
            activeGraphJobId = null
        }
    }

    /** Stops local polling immediately; the DELETE is best effort and only targets our recorded job. */
    private fun stopGraphWork(cancelRemote: Boolean): Job? {
        graphStopRequested = true
        ++graphGeneration
        graphPollJob?.cancel()
        graphPollJob = null
        val gameId = activeGraphGameId
        val jobId = activeGraphJobId
        activeGraphGameId = null
        activeGraphJobId = null
        if (!cancelRemote || gameId == null) return null
        return viewModelScope.launch {
            try {
                withTimeoutOrNull(GRAPH_CANCEL_TIMEOUT_MILLIS) {
                    if (jobId != null) {
                        // A resumed graph may coalesce onto this same remote job. In that case
                        // ownership was adopted again and this old cleanup must leave it alone.
                        if (activeGraphGameId != gameId) {
                            repository.cancelGraphJob(gameId, jobId)
                        }
                    } else {
                        // The POST may become visible just after cancellation. Retry briefly,
                        // but never cancel a job created by a newer graph generation.
                        repeat(GRAPH_CANCEL_LOOKUP_ATTEMPTS) { attempt ->
                            if (activeGraphGameId == gameId) return@withTimeoutOrNull
                            val pending = repository.graphJobStatusForCleanup(gameId).job
                            // graphJobStatusForCleanup suspends. A quick return to this game can
                            // adopt a new job while that request is in flight; never cancel it.
                            if (activeGraphGameId == gameId) return@withTimeoutOrNull
                            if (pending != null && !pending.isTerminal) {
                                repository.cancelGraphJob(gameId, pending.jobId)
                                return@withTimeoutOrNull
                            }
                            if (attempt + 1 < GRAPH_CANCEL_LOOKUP_ATTEMPTS) delay(GRAPH_CANCEL_LOOKUP_MILLIS)
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Leaving a screen must not surface a best-effort cancellation failure.
            }
        }
    }

    private fun graphProgressMessage(state: GraphJobState): String = when (state) {
        GraphJobState.QUEUED -> "Graph analysis queued on PC"
        GraphJobState.RUNNING -> "Building evaluation graph on PC"
        GraphJobState.CANCELLING -> "Stopping graph analysis on PC"
        GraphJobState.COMMITTING -> "Saving evaluation graph on PC"
        else -> "Graph analysis on PC"
    }

    private fun loadExplorer() {
        val game = currentGame ?: return
        if (game.summary.id == DemoFixtures.game.summary.id) return
        viewModelScope.launch { loadExplorer(game.summary.id, currentPly) }
    }

    private suspend fun loadExplorer(gameId: String, ply: Int) {
        try {
            val explorer = repository.explorer(gameId, ply, "masters")
            if (foregroundActive && _uiState.value.screen == AppScreen.ANALYSIS &&
                _uiState.value.analysis.selectedTab != AnalysisTab.GRAPH &&
                currentGame?.summary?.id == gameId && currentPly == ply) {
                updateAnalysis { state -> state.copy(bookMoves = explorer.toBookMoves()) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (foregroundActive && _uiState.value.screen == AppScreen.ANALYSIS &&
                _uiState.value.analysis.selectedTab != AnalysisTab.GRAPH &&
                currentGame?.summary?.id == gameId && currentPly == ply && _uiState.value.analysis.bookMoves.isEmpty()) {
                updateAnalysis { state -> state.copy(message = friendlyMessage(failure)) }
            }
        }
    }

    private suspend fun loadValues(gameId: String) {
        try {
            val cached = repository.cachedLeelaValues(gameId)
            if (foregroundActive && _uiState.value.screen == AppScreen.ANALYSIS &&
                _uiState.value.analysis.selectedTab != AnalysisTab.GRAPH && currentGame?.summary?.id == gameId) {
                cached?.let { updateAnalysis { state -> state.copy(evaluations = it.toEvaluationPoints()) } }
            }
            val values = repository.leelaValues(gameId, calculate = false)
            if (foregroundActive && _uiState.value.screen == AppScreen.ANALYSIS &&
                _uiState.value.analysis.selectedTab != AnalysisTab.GRAPH &&
                currentGame?.summary?.id == gameId && values.available && values.values.isNotEmpty()) {
                updateAnalysis { state -> state.copy(evaluations = values.toEvaluationPoints()) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (currentGame?.summary?.id == gameId && _uiState.value.analysis.selectedTab == AnalysisTab.GRAPH) {
                updateAnalysis { state -> state.copy(message = friendlyMessage(failure)) }
            }
        }
    }

    private fun applySnapshot(snapshot: SearchSnapshot) {
        if (snapshot.contextPly != null && snapshot.contextPly != currentPly) return
        if (snapshot.fen != _uiState.value.analysis.fen) return
        updateAnalysis { it.withSnapshot(snapshot, _uiState.value.showArrows) }
    }

    private fun handleAnalysisAction(action: AnalysisAction) {
        when (action) {
            AnalysisAction.NavigateBack, AnalysisAction.OpenGames -> {
                cancelPositionWork()
                stopGraphWork(cancelRemote = true)
                _uiState.value = _uiState.value.copy(screen = AppScreen.GAMES)
            }
            AnalysisAction.OpenSettings -> onAction(AppAction.OpenSettings)
            AnalysisAction.ToggleEngine -> if (engineEnabled) {
                engineEnabled = false
                cancelPositionWork()
                stopGraphWork(cancelRemote = true)
                updateAnalysis { it.copy(engineThinking = false, message = "Leela paused") }
            } else {
                engineEnabled = true
                startCurrentWork()
            }
            AnalysisAction.Refresh -> {
                engineEnabled = true
                startCurrentWork()
            }
            AnalysisAction.FlipBoard -> updateAnalysis { it.copy(whiteAtBottom = !it.whiteAtBottom) }
            AnalysisAction.FirstMove -> selectPly(0)
            AnalysisAction.PreviousMove -> selectPly(currentPly - 1)
            AnalysisAction.NextMove -> selectPly(currentPly + 1)
            AnalysisAction.LastMove -> selectPly(currentGame?.moves?.size ?: currentPly)
            is AnalysisAction.SelectTab -> {
                val wasGraph = _uiState.value.analysis.selectedTab == AnalysisTab.GRAPH
                updateAnalysis { it.copy(selectedTab = action.tab) }
                if (action.tab == AnalysisTab.GRAPH) {
                    cancelPositionWork()
                    startOrResumeGraph()
                } else if (wasGraph) {
                    stopGraphWork(cancelRemote = true)
                    restartAnalysis()
                }
            }
            is AnalysisAction.SelectPly -> selectPly(action.ply)
            is AnalysisAction.SelectEngineLine -> selectEngineLine(action.index)
            AnalysisAction.More, is AnalysisAction.InspectSquare -> Unit
        }
    }

    private fun selectEngineLine(index: Int) {
        val lines = _uiState.value.analysis.engineLines
        if (index !in lines.indices) return
        val reordered = listOf(lines[index]) + lines.filterIndexed { i, _ -> i != index }
        updateAnalysis { state ->
            state.copy(
                engineLines = reordered.mapIndexed { i, line -> line.copy(isPrimary = i == 0) },
                arrows = if (_uiState.value.showArrows) arrowsFor(reordered) else emptyList(),
            )
        }
    }

    private fun saveAnalysisSettings(
        profile: String = _uiState.value.selectedProfileId,
        nodes: Int = _uiState.value.targetNodes,
        multipv: Int = _uiState.value.multiPv,
    ) {
        val profiles = _uiState.value.availableProfiles.map { it.id }
        val settings = AnalysisSettings(
            profile = profile.takeIf(profiles::contains) ?: EXACT_PROFILE,
            nodes = nodes.coerceIn(1, 100_000),
            multipv = multipv.coerceIn(1, 8),
        )
        repository.saveAnalysisSettings(settings)
        _uiState.value = _uiState.value.copy(
            selectedProfileId = settings.profile,
            targetNodes = settings.nodes,
            multiPv = settings.multipv,
        )
        restartAnalysis()
    }

    private fun updatePreferences(
        autoFetch: Boolean = _uiState.value.autoFetch,
        autoOpenNewest: Boolean = _uiState.value.autoOpenNewest,
        showArrows: Boolean = _uiState.value.showArrows,
        showOpeningBook: Boolean = _uiState.value.showOpeningBook,
    ) {
        val value = AppPreferenceState(autoFetch, autoOpenNewest, showArrows, showOpeningBook)
        preferences.save(value.copy(accountName = _uiState.value.accountName.takeUnless { it == "Demo" }.orEmpty()))
        _uiState.value = _uiState.value.copy(
            autoFetch = autoFetch,
            autoOpenNewest = autoOpenNewest,
            showArrows = showArrows,
            showOpeningBook = showOpeningBook,
        )
    }

    private fun disconnect() {
        viewModelScope.launch {
            cancelPositionWork()
            // Keep the old credential long enough for the tightly bounded graph DELETE.
            stopGraphWork(cancelRemote = true)?.join()
            repository.disconnect()
            savePreferenceState(accountName = "")
            currentGame = null
            _uiState.value = _uiState.value.copy(
                screen = AppScreen.PAIRING,
                accountName = "",
                pcConnected = false,
                hasPairing = false,
                games = emptyList(),
                error = null,
            )
        }
    }

    private fun savePreferenceState(accountName: String = _uiState.value.accountName) {
        preferences.save(
            AppPreferenceState(
                autoFetch = _uiState.value.autoFetch,
                autoOpenNewest = _uiState.value.autoOpenNewest,
                showArrows = _uiState.value.showArrows,
                showOpeningBook = _uiState.value.showOpeningBook,
                accountName = accountName,
            ),
        )
    }

    private fun cancelPositionWork() {
        ++positionGeneration
        positionJob?.cancel()
        positionJob = null
    }

    private fun analysisStateFor(game: GameDetail, ply: Int, previous: AnalysisUiState? = null): AnalysisUiState {
        val move = game.moves.getOrNull(ply - 1)
        val account = _uiState.value.accountName
        val whiteAtBottom = previous?.whiteAtBottom
            ?: !account.equals(game.summary.black.name, ignoreCase = true)
        return AnalysisUiState(
            title = "${game.summary.speed.replaceFirstChar { it.uppercase() }} · ${if (game.summary.rated) "Rated" else "Casual"}",
            players = "${game.summary.white.label()} × ${game.summary.black.label()}",
            fen = game.fenAtPly(ply) ?: game.initialFen,
            whiteAtBottom = whiteAtBottom,
            lastMoveFrom = move?.uci?.take(2),
            lastMoveTo = move?.uci?.drop(2)?.take(2),
            selectedTab = previous?.selectedTab ?: AnalysisTab.LEELA,
            engineConnected = _uiState.value.pcConnected,
            engineThinking = false,
            progress = 0f,
            depth = "—",
            nodesPerSecond = "—",
            nodes = "0",
            elapsed = "0s",
            message = null,
            engineLines = emptyList(),
            bookMoves = emptyList(),
            moves = game.moves.chunked(2).mapIndexed { index, pair ->
                MovePair(
                    number = index + 1,
                    white = pair.getOrNull(0)?.san.orEmpty(),
                    black = pair.getOrNull(1)?.san.orEmpty(),
                    isCurrentWhite = ply == index * 2 + 1,
                    isCurrentBlack = ply == index * 2 + 2,
                )
            },
            evaluations = previous?.evaluations.orEmpty(),
        )
    }

    private fun AnalysisUiState.withSnapshot(snapshot: SearchSnapshot, showArrows: Boolean): AnalysisUiState {
        val visited = snapshot.lines
            .filter { (it.visits ?: 0) > 0 }
            .sortedBy { it.rank }
            .take(_uiState.value.multiPv)
        val lines = visited.mapIndexed { index, line ->
            EngineLine(
                evaluation = line.score,
                moves = line.pvSan.joinToString(" "),
                visits = line.visits?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                policy = line.prior,
                isPrimary = index == 0,
                firstMoveUci = line.pvUci.firstOrNull(),
                visitShare = line.visitShare?.toFloat(),
            )
        }
        val progress = snapshot.progress
        val visits = progress?.visits ?: snapshot.nodes ?: 0
        val target = progress?.target ?: snapshot.targetNodes
        val nps = progress?.nps ?: snapshot.nps ?: visited.firstOrNull()?.nps
        val elapsed = progress?.elapsedMillis ?: snapshot.elapsedMillis
        val first = visited.firstOrNull()
        val snapshotPoint = first?.whiteCentipawns?.let { EvaluationPoint(currentPly, it / 100f) }
        val updatedEvaluations = if (snapshotPoint == null) {
            evaluations
        } else {
            (evaluations.filterNot { it.ply == currentPly } + snapshotPoint).sortedBy { it.ply }
        }
        return copy(
            engineConnected = true,
            engineThinking = snapshot.phase != com.instinctazero.android.model.SearchPhase.FINAL,
            progress = if (target > 0) (visits.toFloat() / target).coerceIn(0f, 1f) else 0f,
            depth = listOfNotNull(first?.depth, first?.selectiveDepth).joinToString("/").ifEmpty { "—" },
            nodesPerSecond = nps?.toString() ?: "—",
            nodes = compact(visits),
            elapsed = "${elapsed / 1000}.${(elapsed % 1000) / 100}s",
            engineLines = lines,
            arrows = if (showArrows) arrowsFor(lines) else emptyList(),
            evaluations = updatedEvaluations,
            message = null,
        )
    }

    private fun updateAnalysis(transform: (AnalysisUiState) -> AnalysisUiState) {
        _uiState.value = _uiState.value.copy(analysis = transform(_uiState.value.analysis))
    }

    private fun currentSettings() = AnalysisSettings(
        nodes = _uiState.value.targetNodes,
        multipv = _uiState.value.multiPv,
        profile = _uiState.value.selectedProfileId,
    )

    private fun handleConnectionFailure(failure: Exception) {
        _uiState.value = _uiState.value.copy(
            pcConnected = false,
            error = friendlyMessage(failure),
            screen = if (failure is RepositoryFailure.Unauthorized) AppScreen.PAIRING else _uiState.value.screen,
        )
    }

    private fun friendlyMessage(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: "InstinctaZero is unavailable. Cached games remain accessible."

    private fun profileUi(id: String) = when (id) {
        "experimental-hetero-int8" -> AnalysisProfileUi(id, "CPU + GPU INT8", "Experimental", true)
        else -> AnalysisProfileUi(EXACT_PROFILE, "SYCL · exact", "Default")
    }

    companion object {
        private const val EXACT_PROFILE = "exact-sycl"
        private const val FOREGROUND_REFRESH_DEBOUNCE_MILLIS = 15_000L
        private const val GRAPH_POLL_MILLIS = 750L
        private const val GRAPH_CANCEL_TIMEOUT_MILLIS = 1_000L
        private const val GRAPH_CANCEL_LOOKUP_ATTEMPTS = 4
        private const val GRAPH_CANCEL_LOOKUP_MILLIS = 150L
    }
}

private fun GameSummary.toUi(account: String): GameSummaryUi {
    val isWhite = white.name.equals(account, ignoreCase = true)
    val opponent = when {
        account.isBlank() -> "${white.name} × ${black.name}"
        isWhite -> black.name
        else -> white.name
    }
    val formatter = DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    return GameSummaryUi(
        id = id,
        opponent = opponent,
        result = result,
        color = if (isWhite) "white" else "black",
        speed = speed.replaceFirstChar { it.uppercase() },
        playedAt = formatter.format(Instant.ofEpochMilli(lastMoveAtMillis)),
        opening = opening?.let { "${it.eco} ${it.name}" }.orEmpty(),
    )
}

private fun com.instinctazero.android.model.Player.label(): String =
    if (rating == null) name else "$name ($rating)"

private fun ExplorerResult.toBookMoves(): List<BookMove> = moves.map { move ->
    val total = move.games.coerceAtLeast(1).toFloat()
    BookMove(move.san, move.games, move.white / total, move.draws / total, move.black / total)
}

private fun LeelaValues.toEvaluationPoints(): List<EvaluationPoint> = values.mapNotNull { point ->
    val whiteValue = when {
        point.wdlMuPawns != null -> point.wdlMuPawns.coerceIn(-3.0, 3.0)
        point.whiteWin != null && point.whiteLoss != null -> (point.whiteWin - point.whiteLoss) * 3.0
        point.q != null && point.sideToMove.equals("black", true) -> -point.q
        else -> point.q
    } ?: return@mapNotNull null
    EvaluationPoint(point.ply, whiteValue.toFloat())
}

private fun arrowsFor(lines: List<EngineLine>): List<BoardArrow> = lines.mapIndexedNotNull { index, line ->
    val uci = line.firstMoveUci ?: return@mapIndexedNotNull null
    if (uci.length < 4) return@mapIndexedNotNull null
    BoardArrow(
        from = uci.take(2),
        to = uci.drop(2).take(2),
        color = if (index == 0) Color(0xB078B9E7) else Color(0x806E6E6E),
        thickness = if (index == 0) 1f else (line.visitShare ?: .35f).coerceIn(.25f, .8f),
    )
}

private fun compact(value: Long): String = when {
    value >= 1_000_000 -> "${(value / 100_000f).roundToInt() / 10f}m"
    value >= 1_000 -> "${(value / 100f).roundToInt() / 10f}k"
    else -> value.toString()
}
