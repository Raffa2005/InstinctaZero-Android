package com.instinctazero.android

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class GameDocumentsTest {
    @Test fun preservesUtf8AndUtf16AndRejectsOversizeOrBrokenEncoding() {
        val pgn="[Event \"Échecs\"]\n\n1.e4 {комментарий} e5 *"
        assertEquals(pgn,GameDocuments.read(ByteArrayInputStream(pgn.toByteArray())))
        assertEquals(pgn,GameDocuments.read(ByteArrayInputStream(pgn.toByteArray(Charsets.UTF_16))))
        assertTrue(runCatching {GameDocuments.read(ByteArrayInputStream(ByteArray(GameDocuments.MAX_BYTES+1)))}.isFailure)
        assertTrue(runCatching {GameDocuments.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)))}.isFailure)
        assertTrue(runCatching {GameDocuments.checkedText("é".repeat(GameDocuments.MAX_BYTES/2+1))}.isFailure)
    }
}
