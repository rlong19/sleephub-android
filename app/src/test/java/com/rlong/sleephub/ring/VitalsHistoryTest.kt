package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.data.SleepNight
import com.rlong.sleephub.ring.data.VitalsCsv
import com.rlong.sleephub.ring.data.VitalsDay
import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class VitalsHistoryTest {
    private val tz = TimeZone.getTimeZone("America/Los_Angeles")
    private val now = 1_760_900_000_000L
    private val p = Parser(tz) { now }

    @Test fun intervalRepliesAreFiveMinuteSteps() {
        // real ring: 2/06 -> 01, 2/08 -> 06 (MOY-R243-2.2.2 log, Oct 19)
        val hr = p.frame(hexBytes("FD DA 10 07 02 06 01")) as RingEvent.TimingInterval
        assertEquals(6, hr.cmd); assertEquals(1, hr.steps)
        val sp = p.frame(hexBytes("FD DA 10 07 02 08 06")) as RingEvent.TimingInterval
        assertEquals(6, sp.steps)
        assertTrue(p.frame(hexBytes("FD DA 10 07 02 2D 0C")) is RingEvent.TimingInterval)
        val temp = p.frame(hexBytes("FD DA 10 07 02 15 06")) as RingEvent.TimingState
        assertEquals(21, temp.cmd)
        val line = Intervals.describe(mapOf(6 to 1, 8 to 6, 7 to 0, 45 to 12), byteArrayOf(6))
        assertEquals("Ring measures HR every 5 min, HRV off, SpO2 every 30 min, stress every 60 min, temp on (state 6)", line)
        assertTrue(Intervals.describe(emptyMap(), null).contains("not read yet"))
    }

    @Test fun timingPagesFilterInvalidAndAssemble() {
        val c = TimingCollector(p)
        val day = 3
        val pg0 = p.frame(Cmd.timingHeartRatePage(day, 0).let { Frame.build(2, 15, byteArrayOf(day.toByte(), 0) + ByteArray(144) { i -> when (i) { 0 -> 0; 1 -> 0xFF.toByte(); 2 -> 72; 3 -> 250.toByte(); else -> 0 } }) }) as RingEvent.TimingPage
        val (next, done) = c.onPage(pg0)
        assertArrayEquals(Cmd.timingPage(15, day, 1), next); assertNull(done)
        val pg1 = p.frame(Frame.build(2, 15, byteArrayOf(day.toByte(), 1) + ByteArray(144) { if (it == 143) 60 else 0 })) as RingEvent.TimingPage
        val s = c.onPage(pg1).second!!
        assertEquals(listOf(72.0, 60.0), s.map { it.value })
        assertEquals(p.dayStart(day) + 2 * 300_000L, s[0].time)
        assertEquals(p.dayStart(day) + 287 * 300_000L, s[1].time)
        assertTrue(s.all { it.source == "timing" })
    }

    @Test fun tempAndHrvTablesRejectAllOnes() {
        val t = p.frame(Frame.build(2, 22, byteArrayOf(1, 0, 0xFF.toByte(), 0xFF.toByte(), 0x64, 0x01, 0x10, 0x27))) as RingEvent.TimingPage
        assertEquals(listOf(null, 35.6, null), t.values)
        val h = p.frame(Frame.build(2, 16, byteArrayOf(1, 0, 0xFF.toByte(), 0xFF.toByte(), 42, 0))) as RingEvent.TimingPage
        assertEquals(listOf(null, 42.0), h.values)
    }

    @Test fun dayStatsOneShotAndCsv() {
        val k = SleepNight.keyFor(now, tz)
        val hr = listOf(Sample(Metric.HEART_RATE, k + 3600_000L, 60.0, "timing"), Sample(Metric.HEART_RATE, k + 3900_000L, 80.0, "timing"),
            Sample(Metric.HEART_RATE, k + 7200_000L, 100.0, "measured"), Sample(Metric.HEART_RATE, k + 7300_000L, 0.0, "timing"))
        val sp = listOf(Sample(Metric.SPO2, k + 1000, 97.0, "timing"), Sample(Metric.SPO2, k + 2000, 255.0, "timing"))
        val d = VitalsDay(k, mapOf(Metric.HEART_RATE to hr, Metric.SPO2 to sp), listOf(k to k + 3600_000L))
        val st = d.stats(Metric.HEART_RATE)!!
        assertEquals(3, st.n); assertEquals(60.0, st.min, 0.0); assertEquals(80.0, st.avg, 0.0); assertEquals(100.0, st.max, 0.0)
        assertEquals(1, d.oneShot(Metric.HEART_RATE).size); assertEquals(2, d.periodic(Metric.HEART_RATE).size)
        assertEquals(1, d.of(Metric.SPO2).size)   // 255 rejected
        assertNull(d.stats(Metric.TEMP)); assertFalse(d.isEmpty)
        assertTrue(VitalsDay(k, emptyMap(), emptyList()).isEmpty)
        val csv = VitalsCsv.toString(d.samples.values.flatten(), tz).trim().split("\r\n")
        assertEquals(VitalsCsv.HEADER, csv[0]); assertEquals(5, csv.size)
        assertTrue(csv[1].endsWith(",SPO2,97.0,%,periodic"))
        assertTrue(csv.any { it.contains(",HEART_RATE,100.0,bpm,one-shot") })
        assertTrue(csv[1].startsWith("2025-10-19T00:00:01-07:00"))
    }
}
