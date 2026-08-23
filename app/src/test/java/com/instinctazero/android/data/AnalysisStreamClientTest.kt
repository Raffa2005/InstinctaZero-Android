package com.instinctazero.android.data

import com.instinctazero.android.model.AnalysisEvent
import com.instinctazero.android.model.AnalysisSettings
import com.instinctazero.android.model.SearchPhase
import com.instinctazero.android.security.SessionCredentials
import com.instinctazero.android.security.SessionStorage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisStreamClientTest {
    @Test
    fun `duplicate done final snapshot is not emitted or attached twice`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                sseResponse(
                    lc0Snapshot(snapshotId = 4, phase = "final") +
                        doneEvent(lc0SnapshotJson(snapshotId = 4, phase = "final")),
                ),
            )
            val events = client(server).observe("game-1", 12, AnalysisSettings()).toList()

            assertEquals(listOf(4), events.filterIsInstance<AnalysisEvent.Snapshot>().map { it.value.snapshotId })
            assertNull(events.filterIsInstance<AnalysisEvent.Completed>().single().finalSnapshot)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `older done final snapshot cannot regress the last emitted generation`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                sseResponse(
                    lc0Snapshot(snapshotId = 4, phase = "final") +
                        doneEvent(lc0SnapshotJson(snapshotId = 3, phase = "final")),
                ),
            )
            val events = client(server).observe("game-1", 12, AnalysisSettings()).toList()

            assertEquals(listOf(4), events.filterIsInstance<AnalysisEvent.Snapshot>().map { it.value.snapshotId })
            assertNull(events.filterIsInstance<AnalysisEvent.Completed>().single().finalSnapshot)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `same snapshot id final phase legitimately advances ready generation`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                sseResponse(
                    lc0Snapshot(snapshotId = 7, phase = "ready") +
                        doneEvent(lc0SnapshotJson(snapshotId = 7, phase = "final")),
                ),
            )
            val events = client(server).observe("game-1", 12, AnalysisSettings()).toList()

            assertEquals(
                listOf(SearchPhase.READY, SearchPhase.FINAL),
                events.filterIsInstance<AnalysisEvent.Snapshot>().map { it.value.phase },
            )
            assertEquals(
                SearchPhase.FINAL,
                events.filterIsInstance<AnalysisEvent.Completed>().single().finalSnapshot?.phase,
            )
        } finally {
            server.shutdown()
        }
    }

    private fun client(server: MockWebServer): AnalysisStreamClient {
        val credentials = SessionCredentials(
            server.url("/").toString().trimEnd('/'),
            "device-token",
            "device-1",
        )
        return AnalysisStreamClient(MobileApiClient(FakeSessionStorage(credentials)))
    }

    private fun sseResponse(events: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            """event: status
data: {"message":"started","request_id":"request-1","fen":"fen","lc0_nodes":1000,"lc0_profile":"exact-sycl","ply":12}

$events""",
        )

    private fun lc0Snapshot(snapshotId: Int, phase: String): String =
        "event: lc0\ndata: ${lc0SnapshotJson(snapshotId, phase)}\n\n"

    private fun doneEvent(finalSnapshot: String): String =
        "event: done\ndata: {\"message\":\"complete\",\"request_id\":\"request-1\",\"fen\":\"fen\",\"ply\":12,\"final_snapshot\":$finalSnapshot}\n\n"

    private fun lc0SnapshotJson(snapshotId: Int, phase: String): String =
        """{"engine":"lc0","ok":true,"nodes":800,"total_nodes":800,"target_nodes":1000,"elapsed_ms":50,"lines":[],"move_stats":[],"root_move_stats":[],"lc0_profile":"exact-sycl","search_phase":"$phase","snapshot_id":$snapshotId,"request_id":"request-1","fen":"fen","ply":12}"""

    private class FakeSessionStorage(initial: SessionCredentials) : SessionStorage {
        private var value: SessionCredentials? = initial
        override fun load(): SessionCredentials? = value
        override fun save(baseUrl: String, bearerToken: String, deviceId: String) {
            value = SessionCredentials(baseUrl, bearerToken, deviceId)
        }
        override fun clear() {
            value = null
        }
    }
}
