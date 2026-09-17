package com.instinctazero.android

import okio.BufferedSource
import okio.utf8Size
import org.json.JSONObject
import java.io.EOFException

/** Framing only, no retries. A clean socket EOF is not an analysis-complete event. */
internal object AnalysisStreamReader {
    internal const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    private fun line(source: BufferedSource): String? = try {
        source.readUtf8LineStrict(MAX_FRAME_BYTES.toLong())
    } catch (eof: EOFException) {
        require(source.buffer.size <= MAX_FRAME_BYTES) { "Analysis frame too large." }
        // An unterminated last line is valid at EOF. Only consume the bounded buffer.
        if (source.buffer.size == 0L) null else source.buffer.readUtf8()
    }
    fun read(source: BufferedSource,current: () -> Boolean,emit: (JSONObject) -> Unit): Boolean {
        var event="message";val data=StringBuilder();var terminal=false;var dataBytes=0L
        fun dispatch() {
            if(data.isNotEmpty() && current()) {
                val frame=JSONObject().put("event",event).put("data",JSONObject(data.toString().trim()))
                terminal=event=="done" || event=="engine-error" || event=="error"
                emit(frame)
            }
            event="message";data.setLength(0);dataBytes=0
        }
        while(current()) {
            val line=line(source) ?: break
            when {
                line.startsWith("event:") -> event=line.substringAfter(':').trim()
                line.startsWith("data:") -> {
                    val value=line.substringAfter(':').trimStart()
                    dataBytes+=value.utf8Size()+1
                    require(dataBytes<=MAX_FRAME_BYTES) { "Analysis frame too large." }
                    data.append(value).append('\n')
                }
                line.isEmpty() -> dispatch()
            }
            if(terminal)return true
        }
        dispatch()
        return terminal
    }
}
