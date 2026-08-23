package com.instinctazero.android.data

import com.instinctazero.android.model.AnalysisEvent
import com.instinctazero.android.model.AnalysisSettings
import com.instinctazero.android.model.RepositoryFailure
import com.instinctazero.android.model.SearchPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal class AnalysisStreamClient(
    private val api: MobileApiClient,
    private val json: Json = MobileApiClient.defaultJson(),
) : AnalysisDataSource {
    @OptIn(InternalCoroutinesApi::class)
    override fun observe(gameId: String, ply: Int, settings: AnalysisSettings): Flow<AnalysisEvent> = flow {
        var retryMillis = 500L
        while (true) {
            currentCoroutineContext().ensureActive()
            val call = api.streamingClient.newCall(api.analysisRequest(gameId, ply, settings))
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { call.cancel() }
            var completed = false
            try {
                call.awaitResponse().use { response ->
                    if (response.code == 401 || response.code == 403) throw RepositoryFailure.Unauthorized()
                    if (!response.isSuccessful) {
                        throw RepositoryFailure.Server(
                            response.code,
                            "Analysis server returned HTTP ${response.code}.",
                        )
                    }
                    val body = response.body
                        ?: throw RepositoryFailure.InvalidResponse("Analysis stream had no response body.")
                    var activeRequestId: String? = null
                    var lastSnapshotId = -1L
                    var lastPhaseRank = -1
                    suspend fun processSnapshot(snapshotDto: Lc0SnapshotDto): com.instinctazero.android.model.SearchSnapshot? {
                        if (snapshotDto.requestId != activeRequestId) return null
                        if (snapshotDto.ply != null && snapshotDto.ply != ply) {
                            throw RepositoryFailure.InvalidResponse("Analysis stream changed to another game ply.")
                        }
                        val phaseRank = phaseRank(snapshotDto.searchPhase)
                        val isNewer = snapshotDto.snapshotId > lastSnapshotId ||
                            (snapshotDto.snapshotId == lastSnapshotId && phaseRank > lastPhaseRank)
                        val snapshot = snapshotDto.toModel()
                        if (!isNewer) return null
                        // Only a complete boundary-committed generation can carry lines.
                        if (snapshot.phase == SearchPhase.PROVISIONAL && snapshot.lines.isNotEmpty()) {
                            return null
                        }
                        lastSnapshotId = snapshot.snapshotId.toLong()
                        lastPhaseRank = phaseRank
                        emit(AnalysisEvent.Snapshot(snapshot))
                        return snapshot
                    }
                    SseParser.parse(body.source()) { frame ->
                        currentCoroutineContext().ensureActive()
                        try {
                            when (frame.event) {
                                "status" -> {
                                    val status = json.decodeFromString<AnalysisStatusDto>(frame.data)
                                    if (status.ply != null && status.ply != ply) {
                                        throw RepositoryFailure.InvalidResponse("Analysis started at another game ply.")
                                    }
                                    activeRequestId = status.requestId
                                    lastSnapshotId = -1L
                                    lastPhaseRank = -1
                                    emit(status.toModel())
                                }
                                "lc0" -> {
                                    val snapshotDto = json.decodeFromString<Lc0SnapshotDto>(frame.data)
                                    processSnapshot(snapshotDto)
                                }
                                "engine-error" -> {
                                    val error = json.decodeFromString<EngineErrorDto>(frame.data)
                                    if (activeRequestId == null || error.requestId == activeRequestId) {
                                        emit(AnalysisEvent.Failed(error.requestId, error.engine, error.error))
                                    }
                                }
                                "done" -> {
                                    val done = json.decodeFromString<AnalysisDoneDto>(frame.data)
                                    if (activeRequestId == null || done.requestId == activeRequestId) {
                                        if (done.ply != null && done.ply != ply) {
                                            throw RepositoryFailure.InvalidResponse("Analysis completed at another game ply.")
                                        }
                                        val finalSnapshot = done.finalSnapshot?.let { processSnapshot(it) }
                                        completed = true
                                        emit(
                                            AnalysisEvent.Completed(
                                                done.requestId,
                                                done.fen,
                                                done.cancelled,
                                                done.message,
                                                done.ply,
                                                finalSnapshot,
                                            ),
                                        )
                                    }
                                }
                            }
                        } catch (failure: SerializationException) {
                            throw RepositoryFailure.InvalidResponse(
                                "The PC sent an incompatible analysis event.",
                                failure,
                            )
                        }
                    }
                }
                if (completed) return@flow
                emit(AnalysisEvent.Failed(null, "transport", "Analysis disconnected; reconnecting."))
            } catch (failure: RepositoryFailure.Unauthorized) {
                throw failure
            } catch (failure: RepositoryFailure.InvalidResponse) {
                throw failure
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                emit(AnalysisEvent.Failed(null, "transport", "Analysis disconnected; reconnecting."))
            } finally {
                cancellation?.dispose()
                call.cancel()
            }
            delay(retryMillis)
            retryMillis = min(retryMillis * 2, 5_000L)
        }
    }.flowOn(Dispatchers.IO)

    private fun phaseRank(phase: String): Int = when (phase) {
        "ready" -> 1
        "final" -> 2
        else -> 0
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }
}
