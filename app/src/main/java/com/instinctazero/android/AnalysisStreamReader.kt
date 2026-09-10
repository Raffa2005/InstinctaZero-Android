package com.instinctazero.android

import okio.BufferedSource
import org.json.JSONObject

/** Framing only, no retries. A clean socket EOF is not an analysis-complete event. */
internal object AnalysisStreamReader {
    fun read(source: BufferedSource,current: () -> Boolean,emit: (JSONObject) -> Unit): Boolean {
        var event="message";val data=StringBuilder();var terminal=false
        fun dispatch() {
            if(data.isNotEmpty() && current()) {
                val frame=JSONObject().put("event",event).put("data",JSONObject(data.toString().trim()))
                terminal=event in listOf("done","engine-error","error")
                emit(frame)
            }
            event="message";data.setLength(0)
        }
        while(current()) {
            val line=source.readUtf8Line() ?: break
            when {
                line.startsWith("event:") -> event=line.substringAfter(':').trim()
                line.startsWith("data:") -> { data.append(line.substringAfter(':').trimStart()).append('\n');require(data.length<=2*1024*1024) { "Analysis frame too large." } }
                line.isEmpty() -> dispatch()
            }
            if(terminal)return true
        }
        dispatch()
        return terminal
    }
}
