package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test

class SyncPlanTest {
    private val d0 = 1_792_393_200_000L          // some local midnight
    private val h = 3600_000L
    private fun cmds(p: SyncPlan) = p.frames.map { "${it[4].toInt() and 0xFF}/${it[5].toInt() and 0xFF}" }

    @Test fun incrementalIsDayZeroPlusUnfinishedYesterday() {
        val p = SyncPlan.build(false, d0 + 15 * h, d0 + 14 * h, yesterdayDone = false, needIntervals = false, dayStart0 = d0)
        assertEquals(listOf(0, 1), p.days)
        val q = SyncPlan.build(false, d0 + 15 * h, d0 + 14 * h, yesterdayDone = true, needIntervals = false, dayStart0 = d0)
        assertEquals(listOf(0), q.days)
        assertEquals(10, q.frames.size)   // sleep, movement, 4 tables, 4 one-shot histories
    }
    @Test fun neverRequestsUnsupportedOrBeyondDay6() {
        val p = SyncPlan.build(true, d0 + 15 * h, 0, false, true, d0)
        assertEquals((0..6).toList(), p.days)
        val bad = setOf("2/45", "2/47", "2/37", "7/0", "7/1", "7/4", "7/12", "7/14")
        assertTrue(cmds(p).none { it in bad })
        assertTrue(p.frames.filter { it.size >= 7 && (it[4].toInt() == 2) && (it[5].toInt() in listOf(14, 15, 16, 17, 19, 22)) }.all { it[6] <= 6 })
    }
    @Test fun dayZeroStartsAtPageCoveringLastSync() {
        // HR/SpO2: 2 pages of 12 h; HRV/temp: 4 pages of 6 h; movement: 8 pages of 3 h
        val p = SyncPlan.build(false, d0 + 15 * h, d0 + 14 * h, true, false, d0)
        assertEquals(1, p.timingStart[15]); assertEquals(1, p.timingStart[17]); assertEquals(2, p.timingStart[16]); assertEquals(4, p.actionStart)
        val morning = SyncPlan.build(false, d0 + 9 * h, d0 + 5 * h, true, false, d0)
        assertEquals(0, morning.timingStart[15]); assertEquals(0, morning.timingStart[16]); assertEquals(1, morning.actionStart)
        val firstToday = SyncPlan.build(false, d0 + 1 * h, d0 - 2 * h, true, false, d0)
        assertTrue(firstToday.timingStart.values.all { it == 0 }); assertEquals(0, firstToday.actionStart)
    }
    @Test fun partialPagesDoNotOverwriteStored() {
        val c = TimingCollector(Parser())
        c.startAt(15, 0, 1)
        val (_, done) = c.onPage(RingEvent.TimingPage(15, Metric.HEART_RATE, 0, 1, List(144) { 70.0 }))
        assertNotNull(done)   // page 0 skipped → padded with nulls, nothing written for it
        val a = ActionCollector(); a.startAt(0, 7)
        val (_, mv) = a.onPage(RingEvent.ActionPage(0, 7, List(180) { 130 }))
        assertEquals(1440, mv!!.size); assertEquals(-1, mv[0]); assertEquals(130, mv[1439])
    }
}
