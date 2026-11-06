package com.instinctazero.android

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Explicit user-selected documents only. Never truncates or rewrites the source. */
internal object GameDocuments {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun read(input: InputStream): String {
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(output.size()<=MAX_BYTES) {
            val count=input.read(buffer,0,minOf(buffer.size,MAX_BYTES+1-output.size()))
            if(count<0)break
            if(count==0)continue
            output.write(buffer,0,count)
        }
        val bytes=output.toByteArray()
        require(bytes.size<=MAX_BYTES) { "Choose a PGN smaller than 8 MiB. Nothing was imported." }
        val utf16=bytes.size>=2 && ((bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte()) || (bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte()))
        return (if(utf16)Charsets.UTF_16 else Charsets.UTF_8).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }
    fun checkedText(text:String):String {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Choose text smaller than 8 MiB. Nothing was imported." }
        return text
    }
}
