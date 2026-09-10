package com.instinctazero.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class AnalysisStreamReaderTest {
    @Test fun socketEofIsNotDoneAndFinalOnlySnapshotsAreDelivered() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("event: lc0\ndata: {\"lines\":[]}\n\n"))
            server.enqueue(MockResponse().setBody("event: done\ndata: {\"final_snapshot\":{\"lines\":[1]}}\n\n"))
            val client=OkHttpClient();val frames=mutableListOf<String>()
            try { for(terminal in listOf(false,true))client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
                assertEquals(terminal,AnalysisStreamReader.read(response.body!!.source(),{true}) { frames.add(it.toString()) })
            } } finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow() }
            assertTrue(frames[1].contains("final_snapshot"));assertEquals(2,server.requestCount)
        }
    }
    @Test fun cancellationBetweenFramesDropsAllStaleDataAndHeartbeatIsNotCompletion() {
        var current=true;var frames=0
        val body=Buffer().writeUtf8(": keepalive\n\nevent: lc0\ndata: {\"lines\":[]}\n\nevent: done\ndata: {\"cancelled\":true}\n\n")
        assertFalse(AnalysisStreamReader.read(body,{current}) { frames++;current=false });assertEquals(1,frames)
        val cancel=Buffer().writeUtf8("event: done\ndata: {\"cancelled\":true}")
        assertTrue(AnalysisStreamReader.read(cancel,{true}) { assertTrue(it.getJSONObject("data").getBoolean("cancelled")) })
        assertFalse(AnalysisStreamReader.read(Buffer().writeUtf8(": keepalive\n\n"),{true}) { fail("Heartbeat must not be data") })
    }
}
