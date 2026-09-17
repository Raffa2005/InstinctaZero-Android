package com.instinctazero.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
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

    @Test fun unterminatedOversizedLinesAreBoundedEvenOutsideDataFields() {
        for (prefix in listOf("data: ", "event: ", ": ")) {
            val limit = AnalysisStreamReader.MAX_FRAME_BYTES
            var bytes = 0L
            val stream = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = minOf(byteCount, 64L * 1024 * 1024 - bytes).toInt()
                    if (count == 0) return -1
                    val data = ByteArray(count) { 'a'.code.toByte() }
                    if (bytes == 0L) prefix.toByteArray().copyInto(data)
                    sink.write(data); bytes += count; return count.toLong()
                }
                override fun timeout() = Timeout.NONE
                override fun close() {}
            }.buffer()
            stream.use {
                assertThrows(IllegalArgumentException::class.java) {
                    AnalysisStreamReader.read(it, { true }) { fail("Oversized frame must not be emitted") }
                }
            }
            assertTrue("Unterminated $prefix line must not read to EOF", bytes <= limit + 8192)
        }
    }
    @Test fun crlfAndMultilineJsonPreserveTerminalFrames() {
        val body = Buffer().writeUtf8("event: done\r\ndata: {\r\ndata: \"cancelled\":false}\r\n\r\n")
        var frames = 0
        assertTrue(AnalysisStreamReader.read(body, { true }) {
            frames++; assertFalse(it.getJSONObject("data").getBoolean("cancelled"))
        })
        assertEquals(1, frames)
    }
    @Test fun manySmallDataLinesCannotExceedTheFrameBudget() {
        val body = Buffer()
        repeat(2049) { body.writeUtf8("data: " + "a".repeat(1024) + "\n") }
        assertThrows(IllegalArgumentException::class.java) { AnalysisStreamReader.read(body, { true }) {} }
    }
}
