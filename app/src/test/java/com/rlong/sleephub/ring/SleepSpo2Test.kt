package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.auto.*
import org.junit.Assert.*
import org.junit.Test

class SleepSpo2Test {
    private val m = 60_000L
    private fun inp(t: Long, steps: Int?, rs: Int? = null, rsAt: Long = 0, end: Long = 0, stage: Int? = null, hr: Double? = null, rest: Double? = 55.0) =
        SleepDetect.Input(t, steps, rs, rsAt, end, stage, hr, rest)

    @Test fun ringSleepStatePreferred() {
        val d = SleepDetect()
        assertTrue(d.update(inp(0, 100, rs = 1, rsAt = 0)))
        assertTrue(d.asleep); assertTrue(d.reason.contains("ring"))
    }
    @Test fun hypnogramGrowingMeansAsleep() {
        val d = SleepDetect(); val now = 100 * m
        d.update(inp(now, 100, end = now - 10 * m, stage = 2)); assertTrue(d.asleep)
        val e = SleepDetect(); e.update(inp(now, 100, end = now - 60 * m, stage = 2)); assertFalse(e.asleep)
    }
    @Test fun stillnessAndLowHrFallbackThenStepsWake() {
        val d = SleepDetect()
        d.update(inp(0, 500, hr = 58.0)); assertFalse(d.asleep)
        d.update(inp(10 * m, 500, hr = 58.0)); assertFalse(d.asleep)
        d.update(inp(20 * m, 500, hr = 58.0)); assertTrue(d.asleep)
        d.update(inp(30 * m, 505)); assertTrue(d.asleep)          // a few steps (rollover) do not wake
        d.update(inp(40 * m, 560)); assertFalse(d.asleep)         // sustained steps
    }
    @Test fun highHrBlocksFallback() {
        val d = SleepDetect()
        d.update(inp(0, 10, hr = 90.0)); d.update(inp(25 * m, 10, hr = 90.0)); assertFalse(d.asleep)
    }
    @Test fun windowAndModes() {
        assertTrue(SpO2When.inWindow(23 * 60, 22 * 60, 7 * 60)); assertTrue(SpO2When.inWindow(3 * 60, 22 * 60, 7 * 60))
        assertFalse(SpO2When.inWindow(12 * 60, 22 * 60, 7 * 60))
        assertFalse(SpO2When.active(SpO2When.SLEEP, false, 23 * 60, 22 * 60, 7 * 60))
        assertTrue(SpO2When.active(SpO2When.SLEEP, true, 13 * 60, 22 * 60, 7 * 60))   // daytime sleep
        assertTrue(SpO2When.active(SpO2When.ALL_DAY, false, 13 * 60, 0, 0))
        assertEquals(22 * 60 + 30, SpO2When.minutes("22:30")); assertNull(SpO2When.minutes("25:00"))
    }
    @Test fun continuousIsExplicitOption() {
        assertEquals(AutoSpo2Logic.CONTINUOUS, AutoSpo2Logic.parseInterval("continuous"))
        assertEquals(10, AutoSpo2Logic.parseInterval("10")); assertEquals(10, AutoSpo2Logic.tickMinutes(-1))
    }
    @Test fun dipsNeed4PointsFor10s() {
        val s = ArrayList<Pair<Long, Double>>()
        for (i in 0 until 180) s += (i * 1000L) to 97.0
        for (i in 180 until 195) s += (i * 1000L) to 92.0      // 15 s at -5
        for (i in 195 until 300) s += (i * 1000L) to 97.0
        for (i in 300 until 305) s += (i * 1000L) to 92.0      // 5 s: too short
        for (i in 305 until 400) s += (i * 1000L) to 97.0
        val d = Dips.find(s)
        assertEquals(1, d.size); assertEquals(180_000L, d[0].start); assertEquals(92.0, d[0].nadir, 0.0)
        assertTrue(Dips.find(List(100) { (it * 1000L) to 95.0 }).isEmpty())
    }
    @Test fun arousalBurstAfterStillnessWithHrRise() {
        val m = 60_000L
        val move = (0 until 60).map { i -> (i * m) to (if (i == 20 || i == 21 || i == 23 || i == 45) 6 else 0) }
        val hr = (0 until 12).map { i -> (i * 5 * m) to (if (i == 9) 70.0 else 56.0) }  // HR spike at 45 min
        val ev = Arousals.find(move, hr, listOf(0L to 60 * m))
        assertEquals(listOf(20 * m, 45 * m), ev.map { it.time })   // 21/23 not ≥5 min still
        assertFalse(ev[0].hrRise); assertTrue(ev[1].hrRise)
        assertEquals(2.0, Arousals.perHour(2, listOf(0L to 60 * m)), 1e-9)
        assertTrue(Arousals.find(move, hr, emptyList()).isEmpty())
    }
}
