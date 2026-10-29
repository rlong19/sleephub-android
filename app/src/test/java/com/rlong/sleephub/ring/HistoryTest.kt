package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.data.*
import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HistoryTest {
    private val tz = TimeZone.getTimeZone("America/Los_Angeles")
    private val key = SleepNight.keyFor(1_760_900_000_000L, tz)   // 2025-10-19 00:00 PDT
    private val MIN = 60_000L
    private fun hour(t: Long) = Calendar.getInstance(tz).apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    @Test fun axisMapsBothWaysAndClamps() {
        val ax = TimeAxis(1000L, 1000L + 24 * 3600_000L, 0.1f, 0.0f)
        assertEquals(100f, ax.x(1000L, 1000), 0.001f)
        assertEquals(1000f, ax.x(ax.t1, 1000), 0.001f)
        assertEquals(550f, ax.x(1000L + 12 * 3600_000L, 1000), 0.001f)
        assertEquals((1000L + 12 * 3600_000L).toDouble(), ax.timeAt(550f, 1000).toDouble(), 3600_000.0 / 900)
        for (t in listOf(ax.t0, ax.t0 + 7 * 3600_000L, ax.t1)) assertEquals(t.toDouble(), ax.timeAt(ax.x(t, 1080), 1080).toDouble(), 120_000.0)
        assertEquals(ax.t0, ax.timeAt(-50f, 1000)); assertEquals(ax.t1, ax.timeAt(5000f, 1000))
    }

    @Test fun windowsNoonAndMidnight() {
        val (a, b) = HistoryWindow.of(key, WindowMode.NOON, tz)
        assertEquals(12, hour(a)); assertEquals(12, hour(b)); assertEquals(key + 12 * 3600_000L, b); assertEquals(24 * 3600_000L, b - a)
        val (c, d) = HistoryWindow.of(key, WindowMode.MIDNIGHT, tz)
        assertEquals(key, c); assertEquals(SleepNight.shiftKey(key, 1, tz), d)
        // DST fall-back day 2025-11-02: noon→noon window is 25 h and still noon-anchored
        val nov2 = SleepNight.keyFor(1_762_100_000_000L, tz)
        val (e, f) = HistoryWindow.of(nov2, WindowMode.NOON, tz)
        assertEquals(12, hour(e)); assertEquals(12, hour(f)); assertEquals(25 * 3600_000L, f - e)
    }

    @Test fun nearestIndexRespectsTolerance() {
        val ts = longArrayOf(0, 5 * MIN, 10 * MIN, 30 * MIN)
        assertEquals(1, Nearest.index(ts, 6 * MIN, 5 * MIN))
        assertEquals(2, Nearest.index(ts, 8 * MIN, 5 * MIN))
        assertEquals(0, Nearest.index(ts, -4 * MIN, 5 * MIN))
        assertEquals(-1, Nearest.index(ts, 20 * MIN, 5 * MIN))     // gap → "—"
        assertEquals(3, Nearest.index(ts, 40 * MIN, 15 * MIN))
        assertEquals(-1, Nearest.index(ts, 46 * MIN, 15 * MIN))
        assertEquals(-1, Nearest.index(LongArray(0), 0, MIN))
        assertEquals(5 * MIN, Nearest.tolerance(Metric.HEART_RATE)); assertEquals(15 * MIN, Nearest.tolerance(Metric.SPO2)); assertEquals(MIN, Nearest.tolerance(null))
    }

    @Test fun stageAndMovementLookup() {
        val segs = listOf(SleepSeg(1, 0, 10 * MIN), SleepSeg(2, 10 * MIN, 20 * MIN))
        assertEquals(1, Nearest.stage(segs, 0)); assertEquals(2, Nearest.stage(segs, 10 * MIN)); assertNull(Nearest.stage(segs, 20 * MIN)); assertNull(Nearest.stage(segs, -1))
        val mv = listOf(0L to 140, MIN to 100, 2 * MIN to 0)
        assertEquals(12, Nearest.movement(mv, 20_000)); assertEquals(0, Nearest.movement(mv, MIN + 10_000)); assertNull(Nearest.movement(mv, 5 * MIN))
    }

    @Test fun readoutPicksNearestAndMarksOneShot() {
        val t0 = key
        val hr = listOf(Sample(Metric.HEART_RATE, t0, 60.0, "timing"), Sample(Metric.HEART_RATE, t0 + 5 * MIN, 65.0, "timing"),
            Sample(Metric.HEART_RATE, t0 + 6 * MIN, 99.0, "measured"))
        val sp = listOf(Sample(Metric.SPO2, t0 + 12 * MIN, 97.0, "timing"), Sample(Metric.SPO2, t0 + 13 * MIN, 0.0, "timing"))
        val v = VitalsDay(key, mapOf(Metric.HEART_RATE to hr, Metric.SPO2 to sp), emptyList())
        val page = HistoryPage(key, t0, t0 + 86400_000L, listOf(SleepSeg(3, t0, t0 + 30 * MIN)), listOf(t0 + 6 * MIN to 135), v, SleepNight(key, emptyList(), emptyList(), emptyList()))
        val r = page.readout(t0 + 6 * MIN)
        assertEquals(3, r.stage); assertEquals(7, r.movement)
        assertEquals("measured", r.values[Metric.HEART_RATE]!!.source)
        assertEquals(97.0, r.values[Metric.SPO2]!!.value, 0.0)       // 6 min away, within ±15; the 0 is filtered
        assertNull(r.values[Metric.TEMP])
        val r2 = page.readout(t0 + 40 * MIN)
        assertNull(r2.stage); assertNull(r2.values[Metric.HEART_RATE]); assertNull(r2.values[Metric.SPO2])
        val txt = com.rlong.sleephub.ring.ui.readoutText(r)
        assertTrue(txt.contains("Stage: REM")); assertTrue(txt.contains("one-shot")); assertTrue(txt.contains("Temperature: —"))
    }
}

class MetricScrubTest {
    @org.junit.Test fun metricPopupMarksAutoVsManualAndTolerance() {
        val m = com.rlong.sleephub.ring.proto.Metric.SPO2
        val t = 1_700_000_000_000L
        val s = listOf(com.rlong.sleephub.ring.proto.Sample(m, t, 97.0, "timing"), com.rlong.sleephub.ring.proto.Sample(m, t + 3_600_000L, 95.0, "history"))
        org.junit.Assert.assertTrue(com.rlong.sleephub.ring.ui.metricReadoutText(m, s, t + 60_000L).contains("97 % • auto"))
        org.junit.Assert.assertTrue(com.rlong.sleephub.ring.ui.metricReadoutText(m, s, t + 3_600_000L).contains("one-shot (manual)"))
        org.junit.Assert.assertTrue(com.rlong.sleephub.ring.ui.metricReadoutText(m, s, t + 1_800_000L).endsWith("—"))
    }
}
