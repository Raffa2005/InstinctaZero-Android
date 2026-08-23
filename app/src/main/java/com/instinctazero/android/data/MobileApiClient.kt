package com.instinctazero.android.data

import com.instinctazero.android.model.AnalysisSettings
import com.instinctazero.android.model.RepositoryFailure
import com.instinctazero.android.security.SecureSessionStore
import com.instinctazero.android.security.SessionStorage
import com.instinctazero.android.security.SessionCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit

internal class DeviceAuthInterceptor(private val store: SessionStorage) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val credentials = store.load() ?: throw RepositoryFailure.NotPaired()
        val server = credentials.baseUrl.toHttpUrl()
        val requestUrl = chain.request().url
        if (requestUrl.scheme != server.scheme || requestUrl.host != server.host || requestUrl.port != server.port) {
            throw RepositoryFailure.Unavailable("Refused to send a device token to another server.")
        }
        return chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer ${credentials.bearerToken}")
                .build(),
        )
    }
}

internal class MobileApiClient(
    private val sessionStore: SessionStorage,
    private val json: Json = defaultJson(),
    baseClient: OkHttpClient = OkHttpClient(),
) : RemoteDataSource {
    private val unauthenticatedClient = baseClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    internal val authenticatedClient: OkHttpClient = baseClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(DeviceAuthInterceptor(sessionStore))
        .build()
    internal val streamingClient: OkHttpClient = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(DeviceAuthInterceptor(sessionStore))
        .build()

    override suspend fun claimPairing(baseUrl: String, code: String, deviceName: String): PairClaimResponseDto {
        val safeBaseUrl = SecureSessionStore.normalizeSecureBaseUrl(baseUrl)
        val body = json.encodeToString(PairClaimRequestDto(code.trim(), deviceName.trim()))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint(safeBaseUrl, "pair", "claim"))
            .post(body)
            .header("Accept", "application/json")
            .build()
        return executeJson(unauthenticatedClient, request)
    }

    override suspend fun session(): SessionDto = get("session")

    override suspend fun startSync(): SyncEnvelopeDto {
        val request = authenticatedRequest("sync").post(EMPTY_JSON_BODY).build()
        return executeJson(authenticatedClient, request)
    }

    override suspend fun syncState(): SyncEnvelopeDto = get("sync")

    override suspend fun games(cursor: String?, limit: Int): GamesPageDto {
        val url = endpointFromSession("games").newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .apply { if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor) }
            .build()
        return executeJson(authenticatedClient, authenticatedRequest(url).get().build())
    }

    override suspend fun game(gameId: String): GameEnvelopeDto = get("games", gameId)

    override suspend fun explorer(gameId: String, ply: Int, source: String): ExplorerEnvelopeDto {
        val url = endpointFromSession("games", gameId, "explorer").newBuilder()
            .addQueryParameter("ply", ply.coerceAtLeast(0).toString())
            .addQueryParameter("source", if (source == "lichess") "lichess" else "masters")
            .build()
        return executeJson(authenticatedClient, authenticatedRequest(url).get().build())
    }

    override suspend fun values(gameId: String, calculate: Boolean): LeelaValuesDto {
        return (if (calculate) startGraph(gameId) else graphStatus(gameId)).valuesDto()
    }

    override suspend fun graphStatus(gameId: String): GraphStatusDto =
        executeJson(
            authenticatedClient,
            authenticatedRequest(endpointFromSession("games", gameId, "values")).get().build(),
        )

    override suspend fun startGraph(gameId: String): GraphStatusDto =
        executeJson(
            authenticatedClient,
            authenticatedRequest(endpointFromSession("games", gameId, "values"))
                .post(EMPTY_BODY)
                .build(),
        )

    override suspend fun cancelGraph(gameId: String, jobId: String): GraphJobEnvelopeDto {
        val url = endpointFromSession("games", gameId, "values").newBuilder()
            .addQueryParameter("job_id", jobId)
            .build()
        return executeJson(authenticatedClient, authenticatedRequest(url).delete().build())
    }

    override suspend fun revokeSession(): RevokeResponseDto {
        val request = authenticatedRequest("session").delete().build()
        return executeJson(authenticatedClient, request)
    }

    override suspend fun revokeSession(credentials: SessionCredentials?): RevokeResponseDto {
        credentials ?: throw RepositoryFailure.NotPaired()
        val request = Request.Builder()
            .url(endpoint(credentials.baseUrl, "session"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .delete()
            .build()
        return executeJson(unauthenticatedClient, request)
    }

    fun analysisRequest(gameId: String, ply: Int, settings: AnalysisSettings): Request {
        val url = endpointFromSession("games", gameId, "analysis", "stream").newBuilder()
            .addQueryParameter("ply", ply.coerceAtLeast(0).toString())
            .addQueryParameter("nodes", settings.nodes.coerceIn(1, 100_000).toString())
            .addQueryParameter("multipv", settings.multipv.coerceIn(1, 8).toString())
            .addQueryParameter("profile", settings.profile)
            .build()
        return authenticatedRequest(url)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
    }

    private suspend inline fun <reified T> get(vararg path: String): T {
        val request = authenticatedRequest(*path).get().build()
        return executeJson(authenticatedClient, request)
    }

    private fun authenticatedRequest(vararg path: String): Request.Builder =
        authenticatedRequest(endpointFromSession(*path))

    private fun authenticatedRequest(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")

    private fun endpointFromSession(vararg path: String): HttpUrl {
        val credentials = sessionStore.load() ?: throw RepositoryFailure.NotPaired()
        return endpoint(credentials.baseUrl, *path)
    }

    private fun endpoint(baseUrl: String, vararg path: String): HttpUrl = baseUrl.toHttpUrl()
        .newBuilder()
        .addPathSegments("api/mobile/v1")
        .apply { path.forEach(::addPathSegment) }
        .build()

    @OptIn(InternalCoroutinesApi::class)
    private suspend inline fun <reified T> executeJson(client: OkHttpClient, request: Request): T =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { call.cancel() }
            try {
                val response = try {
                    call.awaitResponse()
                } catch (failure: RepositoryFailure) {
                    throw failure
                } catch (failure: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw RepositoryFailure.Unavailable("Cannot reach the InstinctaZero PC.", failure)
                }
                response.use {
                    val responseText = it.body?.string().orEmpty()
                    if (!it.isSuccessful) throw responseFailure(it.code, responseText)
                    try {
                        json.decodeFromString<T>(responseText)
                    } catch (failure: SerializationException) {
                        throw RepositoryFailure.InvalidResponse("The PC returned an incompatible response.", failure)
                    }
                }
            } finally {
                cancellation?.dispose()
                call.cancel()
            }
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

    private fun responseFailure(status: Int, body: String): RepositoryFailure {
        if (status == 401 || status == 403) return RepositoryFailure.Unauthorized()
        val parsed = runCatching { json.decodeFromString<ApiErrorDto>(body) }.getOrNull()
        val message = parsed?.error ?: parsed?.detail ?: "InstinctaZero server returned HTTP $status."
        return RepositoryFailure.Server(status, message)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}
