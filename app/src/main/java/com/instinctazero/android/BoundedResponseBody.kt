package com.instinctazero.android

import okhttp3.ResponseBody
import java.io.IOException

/** Enforce a byte budget before allocating a decoded response string, including
 * unknown-length/chunked responses. The caller owns and closes the response. */
internal fun ResponseBody?.boundedString(maximumBytes: Int): String {
    require(maximumBytes >= 0)
    if (this == null) return ""
    if (contentLength() > maximumBytes || source().request(maximumBytes.toLong() + 1)) {
        throw IOException("Gateway response is too large.")
    }
    return string()
}
