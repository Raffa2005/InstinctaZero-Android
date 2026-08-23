package com.instinctazero.android.data

import com.instinctazero.android.model.SearchPhase
import com.instinctazero.android.model.GraphJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoMappersTest {
    @Test
    fun `graph job committing state remains active and owned`() {
        val status = GraphStatusDto(
            job = GraphJobDto(
                jobId = "job-1",
                gameId = "game-1",
                state = "committing",
                createdAt = "2026-08-23T12:00:00Z",
                updatedAt = "2026-08-23T12:00:10Z",
            ),
        ).toModel()

        assertEquals(GraphJobState.COMMITTING, status.job?.state)
        assertEquals("game-1", status.job?.gameId)
        assertFalse(status.job!!.isTerminal)
        assertNull(status.values)
    }

    @Test
    fun `cached graph response maps values without requiring a job`() {
        val status = GraphStatusDto(
            available = true,
            cached = true,
            settingsHash = "settings-1",
            values = listOf(
                LeelaValuePointDto(
                    ply = 4,
                    value = LeelaValueDto(available = true, q = .2),
                ),
            ),
        ).toModel()

        assertTrue(status.available)
        assertTrue(status.cached)
        assertEquals("settings-1", status.settingsHash)
        assertEquals(.2, status.values!!.values.single().q!!, 0.0)
        assertNull(status.job)
    }

    @Test
    fun `snapshot mapping publishes one coherent engine update`() {
        val snapshot = Lc0SnapshotDto(
            nodes = 900,
            totalNodes = 1_240,
            targetNodes = 2_000,
            elapsedMillis = 375,
            lines = listOf(
                AnalysisLineDto(
                    multipv = 1,
                    score = "+0.44",
                    whiteCentipawns = 44,
                    depth = 10,
                    seldepth = 16,
                    nodes = 800,
                    nps = 3_300,
                    pv = listOf("e2e4", "e7e5"),
                    san = listOf("e4", "e5"),
                ),
                AnalysisLineDto(
                    multipv = 2,
                    score = "+0.18",
                    pv = listOf("d2d4", "g8f6"),
                    san = listOf("d4", "Nf6"),
                ),
            ),
            moveStats = listOf(
                MoveStatDto("e2e4", visits = 750, prior = .32),
                MoveStatDto("d2d4", visits = 250, prior = .21),
                MoveStatDto("g1f3", visits = 0, prior = .15),
            ),
            lc0Profile = "exact-sycl",
            approximate = false,
            searchPhase = "ready",
            snapshotId = 7,
            requestId = "request-7",
            fen = "test-fen w - - 0 1",
        ).toModel()

        assertEquals("request-7", snapshot.requestId)
        assertEquals(7, snapshot.snapshotId)
        assertEquals(SearchPhase.READY, snapshot.phase)
        assertEquals(1_240L, snapshot.nodes)
        assertEquals(2_000L, snapshot.targetNodes)
        assertEquals("exact-sycl", snapshot.profile)
        assertFalse(snapshot.approximate)
        assertEquals(listOf("e2e4", "e7e5"), snapshot.lines[0].pvUci)
        assertEquals(listOf("e4", "e5"), snapshot.lines[0].pvSan)
        assertEquals(750L, snapshot.lines[0].visits)
        assertEquals(.32, snapshot.lines[0].prior!!, 0.0)
        assertEquals(.75, snapshot.lines[0].visitShare!!, 0.000_001)
        assertEquals(.25, snapshot.lines[1].visitShare!!, 0.000_001)
    }

    @Test
    fun `snapshot with no visits preserves prior and leaves share unknown`() {
        val snapshot = Lc0SnapshotDto(
            targetNodes = 1_000,
            lines = listOf(AnalysisLineDto(pv = listOf("c2c4"))),
            moveStats = listOf(MoveStatDto("c2c4", visits = 0, prior = .27)),
            requestId = "cold-start",
            fen = "fen",
        ).toModel()

        assertEquals(0L, snapshot.lines.single().visits)
        assertEquals(.27, snapshot.lines.single().prior!!, 0.0)
        assertNull(snapshot.lines.single().visitShare)
        assertEquals(SearchPhase.PROVISIONAL, snapshot.phase)
    }

    @Test
    fun `unrecognized phase is safely provisional`() {
        val snapshot = Lc0SnapshotDto(
            requestId = "request",
            fen = "fen",
            searchPhase = "future-server-phase",
        ).toModel()

        assertEquals(SearchPhase.PROVISIONAL, snapshot.phase)
    }

    @Test
    fun `leela values infer availability from populated legacy response`() {
        val values = LeelaValuesDto(
            available = null,
            cached = true,
            values = listOf(
                LeelaValuePointDto(
                    ply = 12,
                    value = LeelaValueDto(
                        available = true,
                        q = .4,
                        sideToMove = "white",
                        whiteWdl = WhiteWdlDto(win = .55, draw = .30, loss = .15),
                    ),
                    elapsedMillis = 40,
                ),
            ),
        ).toModel()

        assertTrue(values.available)
        assertTrue(values.cached)
        assertEquals(12, values.values.single().ply)
        assertEquals(.55, values.values.single().whiteWin!!, 0.0)
        assertEquals(.30, values.values.single().whiteDraw!!, 0.0)
        assertEquals(.15, values.values.single().whiteLoss!!, 0.0)
    }
}
