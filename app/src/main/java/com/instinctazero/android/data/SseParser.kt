package com.instinctazero.android.data

import okio.BufferedSource

internal data class SseFrame(
    val event: String,
    val data: String,
    val id: String? = null,
)

/** Minimal WHATWG-compatible SSE framing parser, independent of Android APIs. */
internal object SseParser {
    suspend fun parse(source: BufferedSource, onFrame: suspend (SseFrame) -> Unit) {
        var event = "message"
        var id: String? = null
        val data = mutableListOf<String>()

        suspend fun dispatch() {
            if (data.isNotEmpty()) onFrame(SseFrame(event, data.joinToString("\n"), id))
            event = "message"
            data.clear()
        }

        var firstLine = true
        while (true) {
            val rawLine = source.readUtf8Line() ?: break
            val line = if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine
            firstLine = false
            if (line.isEmpty()) {
                dispatch()
                continue
            }
            if (line.startsWith(':')) continue
            val separator = line.indexOf(':')
            val field = if (separator >= 0) line.substring(0, separator) else line
            val rawValue = if (separator >= 0) line.substring(separator + 1) else ""
            val value = rawValue.removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> data += value
                "id" -> if ('\u0000' !in value) id = value
            }
        }
        dispatch()
    }
}
