package com.instinctazero.android

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Connection and replay policy for the fixed InstinctaZero gateway.
 *
 * OkHttp must be allowed to recover from a failed route so that it can try the next address from
 * DNS. Requests with state-changing or long-lived POST semantics use [nonReplayable]: they may
 * move to another route while establishing the connection, but cannot be replayed after request
 * transmission has begun.
 */
internal object GatewayHttpPolicy {
    fun restClient(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun streamClient(restClient: OkHttpClient): OkHttpClient = restClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun nonReplayable(body: RequestBody): RequestBody = NonReplayableRequestBody(body)

    private class NonReplayableRequestBody(
        private val delegate: RequestBody,
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isDuplex(): Boolean = delegate.isDuplex()
        override fun isOneShot(): Boolean = true
    }
}
