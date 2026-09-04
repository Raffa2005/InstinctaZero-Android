package com.instinctazero.android

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayHttpPolicyTest {
    @Test
    fun idempotentRestRequestFallsThroughToTheNextDnsAddress() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setResponseCode(200).setBody("healthy ingress"))
            val client = GatewayHttpPolicy.restClient().newBuilder()
                .dns(twoAddressDns())
                .connectTimeout(1, TimeUnit.SECONDS)
                .build()

            client.newCall(
                Request.Builder().url("http://funnel.test:${server.port}/api/mobile/v1/status").get().build(),
            ).execute().use { response ->
                assertTrue(response.isSuccessful)
                assertEquals("healthy ingress", response.body?.string())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun nonReplayablePostCanFailOverBeforeTransmissionBegins() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setResponseCode(200).setBody("accepted once"))
            val client = GatewayHttpPolicy.restClient().newBuilder()
                .dns(twoAddressDns())
                .connectTimeout(1, TimeUnit.SECONDS)
                .build()
            val body = GatewayHttpPolicy.nonReplayable("{}".toRequestBody())

            client.newCall(
                Request.Builder().url("http://funnel.test:${server.port}/api/mobile/v1/sync")
                    .post(body).build(),
            ).execute().use { response ->
                assertTrue(response.isSuccessful)
                assertEquals("accepted once", response.body?.string())
            }
            assertEquals(1, server.requestCount)
            assertTrue(body.isOneShot())
        }
    }

    @Test
    fun analysisPostFallsThroughATlsHandshakeFailureBeforeTransmission() {
        val certificate = HeldCertificate.Builder()
            .commonName("funnel.test")
            .addSubjectAlternativeName("funnel.test")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        MockWebServer().use { failedIngress ->
            MockWebServer().use { healthyIngress ->
                failedIngress.useHttps(serverCertificates.sslSocketFactory(), false)
                healthyIngress.useHttps(serverCertificates.sslSocketFactory(), false)
                failedIngress.enqueue(MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE))
                healthyIngress.enqueue(MockResponse().setResponseCode(200).setBody("analysis stream"))
                failedIngress.start(InetAddress.getByName("127.0.0.2"), 0)
                healthyIngress.start(InetAddress.getByName("127.0.0.1"), failedIngress.port)
                val client = GatewayHttpPolicy.streamClient(GatewayHttpPolicy.restClient()).newBuilder()
                    .dns(twoAddressDns())
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .build()
                val body = GatewayHttpPolicy.nonReplayable("analysis".toRequestBody())

                client.newCall(
                    Request.Builder().url("https://funnel.test:${failedIngress.port}/analysis")
                        .post(body).build(),
                ).execute().use { response ->
                    assertTrue(response.isSuccessful)
                    assertEquals("analysis stream", response.body?.string())
                }
                val failedRequest = failedIngress.takeRequest(100, TimeUnit.MILLISECONDS)
                assertNotNull(failedRequest)
                assertEquals("", failedRequest?.requestLine)
                assertEquals(0L, failedRequest?.bodySize)
                assertEquals(1, healthyIngress.requestCount)
            }
        }
    }

    @Test
    fun nonReplayablePostIsNotSentAgainAfterTransmissionBegins() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("0.0.0.0"), 0)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must not be reached"))
            val client = GatewayHttpPolicy.restClient().newBuilder()
                .dns(twoAddressDns())
                .connectTimeout(1, TimeUnit.SECONDS)
                .build()
            val body = GatewayHttpPolicy.nonReplayable(ByteArray(1024 * 1024).toRequestBody())

            try {
                client.newCall(
                    Request.Builder().url("http://funnel.test:${server.port}/api/mobile/v1/sync")
                        .post(body).build(),
                ).execute().close()
                fail("a partially transmitted one-shot request must fail instead of replaying")
            } catch (_: java.io.IOException) {
                // Expected: the server saw request bytes, so retrying could duplicate the effect.
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun clientPoliciesEnableRouteRecoveryButKeepAnalysisSingleShot() {
        val rest = GatewayHttpPolicy.restClient()
        val stream = GatewayHttpPolicy.streamClient(rest)
        val analysisBody = GatewayHttpPolicy.nonReplayable("analysis".toRequestBody())

        assertTrue(rest.retryOnConnectionFailure)
        assertEquals(30_000, rest.readTimeoutMillis)
        assertTrue(stream.retryOnConnectionFailure)
        assertEquals(0, stream.readTimeoutMillis)
        assertTrue(analysisBody.isOneShot())
    }

    private fun twoAddressDns() = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(
            InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 2)),
            InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)),
        )
    }
}
