package com.instinctazero.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BoundedResponseBodyTest {
    private class CountingBody(private val length: Long, private val reportedLength: Long = -1) : ResponseBody() {
        var bytesRead = 0L
        var closed = false
        private val buffered = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = minOf(byteCount, length - bytesRead).toInt()
                if (count == 0) return -1
                sink.write(ByteArray(count) { 'a'.code.toByte() }); bytesRead += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = reportedLength
        override fun source(): BufferedSource = buffered
    }

    @Test fun oversizedKnownLengthIsRejectedWithoutReading() {
        val body = CountingBody(1_000_000, 1_000_000)
        body.use { assertThrows(IOException::class.java) { it.boundedString(1024) } }
        assertEquals(0L, body.bytesRead); assertTrue(body.closed)
    }
    @Test fun unknownAndMisreportedLengthsStopWithinOneBufferSegmentOfTheBudget() {
        for (reported in listOf(-1L, 10L)) {
            val body = CountingBody(1_000_000_000, reported)
            body.use { assertThrows(IOException::class.java) { it.boundedString(1024) } }
            assertTrue(body.bytesRead <= 1024 + 8192); assertTrue(body.closed)
        }
    }
    @Test fun boundariesEmptyResponsesAndUnicodeAreMeasuredInBytes() {
        assertEquals("", (null as ResponseBody?).boundedString(0))
        assertEquals("", "".toResponseBody().boundedString(0))
        assertEquals("éé", "éé".toResponseBody().boundedString(4))
        "éé".toResponseBody().use { assertThrows(IOException::class.java) { it.boundedString(3) } }
        assertEquals("a".repeat(1024), CountingBody(1024).boundedString(1024))
    }
    @Test fun okhttpCharsetAndBomDecodingArePreserved() {
        val latin1 = byteArrayOf(0xe9.toByte()).toResponseBody("text/plain; charset=iso-8859-1".toMediaType())
        assertEquals("é", latin1.boundedString(1))
        val bom = Buffer().writeHex("fffe6100").readByteArray().toResponseBody("text/plain; charset=utf-8".toMediaType())
        assertEquals("a", bom.boundedString(4))
    }
}
