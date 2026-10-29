package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.auto.AutoSpo2Logic
import com.rlong.sleephub.ring.auto.AutoSpo2Logic.Decision
import com.rlong.sleephub.ring.data.HistoryWindow
import com.rlong.sleephub.ring.data.SleepNight
import com.rlong.sleephub.ring.data.Vitals
import com.rlong.sleephub.ring.data.VitalsDay
import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class AutoSpo2Test {
    @Test fun invalidValuesDiscarded() {
        for (v in listOf(0.0, 255.0, 69.0, 101.0, 50.0)) assertFalse("$v", AutoSpo2Logic.valid(v))
        assertFalse(AutoSpo2Logic.valid(null))
        for (v in listOf(70.0, 95.0, 99.0, 100.0)) assertTrue("$v", AutoSpo2Logic.valid(v))
    }
    @Test fun runsWhenIdle() = assertEquals(Decision.Run, AutoSpo2Logic.decide(15, true, false, false, 0))
    @Test fun defersWhileBusyOrDisconnected() {
        assertTrue((AutoSpo2Logic.decide(15, true, false, true, 3) as Decision.Defer).why.contains("measurement"))
        val d = AutoSpo2Logic.decide(15, false, false, false, 0) as Decision.Defer
        assertEquals(AutoSpo2Logic.CONNECT_WAIT_MS, d.ms)
    }
    /** v13 bug: a 20+ min full sync deferred every tick. Now a tick pre-empts the sync immediately. */
    @Test fun syncDoesNotDefer() {
        assertEquals(Decision.Run, AutoSpo2Logic.decide(5, true, true, false, 0))
        assertTrue(AutoSpo2Logic.decide(5, true, true, true, 0) is Decision.Defer) // but not over another measure
    }
    @Test fun givesUpAfterMaxDefers() {
        assertTrue(AutoSpo2Logic.decide(15, false, false, false, AutoSpo2Logic.MAX_DEFERS) is Decision.Skip)
        assertTrue(AutoSpo2Logic.decide(0, true, false, false, 0) is Decision.Skip)
    }
    @Test fun nextTickAnchoredAndNeverPast() {
        val now = 1_000_000_000L
        assertEquals(now + AutoSpo2Logic.FIRST_TICK_MS, AutoSpo2Logic.next(0, 15, now))  // soon after enabling
        assertEquals(now - 60_000L + 15 * 60_000L, AutoSpo2Logic.next(now - 60_000L, 15, now))
        assertEquals(now + 5_000L, AutoSpo2Logic.next(now - 3600_000L, 15, now))
        assertNull(AutoSpo2Logic.next(now, 0, now))
    }
    @Test fun intervalParsing() {
        assertEquals(15, AutoSpo2Logic.parseInterval(null)); assertEquals(0, AutoSpo2Logic.parseInterval("0"))
        assertEquals(5, AutoSpo2Logic.parseInterval("5")); assertEquals(15, AutoSpo2Logic.parseInterval("7"))
    }
    @Test fun appAutoSeparatedFromOneShot() {
        val l = listOf(Sample(Metric.SPO2, 1, 97.0, "app-auto"), Sample(Metric.SPO2, 2, 99.0, "measured"), Sample(Metric.SPO2, 3, 96.0, "timing"))
        val d = VitalsDay(0, mapOf(Metric.SPO2 to l), emptyList())
        assertEquals(1, d.appAuto(Metric.SPO2).size); assertEquals(1, d.oneShot(Metric.SPO2).size); assertEquals(1, d.periodic(Metric.SPO2).size)
        assertEquals(" • auto (app)", Vitals.popupTag(l[0]))
    }
    /** Real 2/0B reply from Richard's ring: FD DA 10 0C 02 0B 01 63 A7 A9 D5 6A → 99% at 2026-10-19 13:24:55 PDT. */
    @Test fun spo2HistoryTimestamp() {
        val tz = TimeZone.getTimeZone("America/Los_Angeles")
        val e = Parser(tz).frame(hexBytes("FD DA 10 0C 02 0B 01 63 A7 A9 D5 6A")) as RingEvent.History
        val s = e.samples.single()
        assertEquals(99.0, s.value, 0.0)
        assertEquals(1792441495000L, s.time) // 20:24:55Z
    }
    @Test fun afternoonReadingOpensInProgressWindow() {
        val tz = TimeZone.getTimeZone("America/Los_Angeles")
        val reading = 1792441495000L            // Oct 19 13:24:55 PDT
        val now = reading + 40 * 60_000L
        val mode = com.rlong.sleephub.ring.data.WindowMode.NOON
        assertEquals(HistoryWindow.partialKey(now, mode, tz), HistoryWindow.defaultKey(reading, now, mode, tz))
        val (p0, p1) = HistoryWindow.partialWindow(now, tz)
        assertTrue(reading in p0..p1)
        // the noon→noon in-progress night page also contains it
        val (t0, t1) = HistoryWindow.of(HistoryWindow.keyContaining(reading, mode, tz), mode, tz)
        assertTrue(reading in t0 until t1)
        val yesterdayEve = SleepNight.keyFor(now, tz) - 3600_000L
        assertEquals(HistoryWindow.keyContaining(yesterdayEve, mode, tz), HistoryWindow.defaultKey(yesterdayEve, now, mode, tz))
    }
}
