package com.instinctazero.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureSessionStoreTest {
    @Test
    fun `secure server url is normalized without weakening transport`() {
        assertEquals(
            "https://analysis.example.test:8443/path",
            SecureSessionStore.normalizeSecureBaseUrl(
                "  https://analysis.example.test:8443/path///  ",
            ),
        )
    }

    @Test
    fun `pairing rejects insecure or credential-bearing server urls`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureSessionStore.normalizeSecureBaseUrl("http://analysis.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureSessionStore.normalizeSecureBaseUrl("https://user:secret@analysis.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureSessionStore.normalizeSecureBaseUrl("https://analysis.example.test/#fragment")
        }
    }
}
