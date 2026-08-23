package com.instinctazero.android.data

import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun `frame is delivered only after its complete blank-line boundary`() = runTest {
        val source = Buffer().writeUtf8(
            "event: lc0\n" +
                "id: generation-12\n" +
                "data: {\"snapshot_id\":12,\n" +
                "data: \"phase\":\"ready\"}\n" +
                "\n" +
                "event: done\n" +
                "data: {\"message\":\"complete\"}\n\n",
        )
        val frames = mutableListOf<SseFrame>()

        SseParser.parse(source, frames::add)

        assertEquals(
            listOf(
                SseFrame(
                    event = "lc0",
                    id = "generation-12",
                    data = "{\"snapshot_id\":12,\n\"phase\":\"ready\"}",
                ),
                SseFrame(
                    event = "done",
                    id = "generation-12",
                    data = "{\"message\":\"complete\"}",
                ),
            ),
            frames,
        )
    }

    @Test
    fun `parser handles bom comments crlf and eof terminated final frame`() = runTest {
        val source = Buffer().writeUtf8(
            "\uFEFF: connected\r\n" +
                "event: status\r\n" +
                "data: {\"request_id\":\"abc\"}\r\n" +
                "\r\n" +
                ": keepalive\r\n" +
                "data: final-without-blank-line",
        )
        val frames = mutableListOf<SseFrame>()

        SseParser.parse(source, frames::add)

        assertEquals(2, frames.size)
        assertEquals("status", frames[0].event)
        assertEquals("{\"request_id\":\"abc\"}", frames[0].data)
        assertEquals("message", frames[1].event)
        assertEquals("final-without-blank-line", frames[1].data)
    }

    @Test
    fun `unknown fields and nul ids cannot corrupt the next event`() = runTest {
        val source = Buffer().writeUtf8(
            "id: stable\n" +
                "retry: 1000\n" +
                "data: first\n\n" +
                "id: ignored\u0000id\n" +
                "unknown: ignored\n" +
                "event: lc0\n" +
                "data: second\n\n",
        )
        val frames = mutableListOf<SseFrame>()

        SseParser.parse(source, frames::add)

        assertEquals("stable", frames[0].id)
        assertEquals("stable", frames[1].id)
        assertEquals("lc0", frames[1].event)
        assertEquals("second", frames[1].data)
    }

    @Test
    fun `empty stream and comment-only blocks emit no synthetic events`() = runTest {
        val frames = mutableListOf<SseFrame>()

        SseParser.parse(Buffer().writeUtf8(": ping\n\n\n"), frames::add)

        assertTrue(frames.isEmpty())
    }
}
