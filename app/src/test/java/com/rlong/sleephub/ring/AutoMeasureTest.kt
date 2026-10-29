package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test

class AutoMeasureTest {
    @Test fun writesMatchSdkEncoding() {
        // b1.h.c(3) / t.c(1) / u.c(0) / h0.c(12) → q.c(1, cmd, [steps])
        assertArrayEquals(hexBytes("FD DA 10 07 01 08 03"), AutoMeasure.write(Metric.SPO2, 15))
        assertArrayEquals(hexBytes("FD DA 10 07 01 06 01"), AutoMeasure.write(Metric.HEART_RATE, 5))
        assertArrayEquals(hexBytes("FD DA 10 07 01 07 00"), AutoMeasure.write(Metric.HRV, 0))
        assertArrayEquals(hexBytes("FD DA 10 07 01 27 0C"), AutoMeasure.write(Metric.STRESS, 60))
        assertArrayEquals(hexBytes("FD DA 10 07 01 08 06"), AutoMeasure.write(Metric.SPO2, 30))
    }
    @Test fun queriesAndReadBack() {
        assertArrayEquals(hexBytes("FD DA 10 06 02 08"), AutoMeasure.query(Metric.SPO2))
        assertArrayEquals(hexBytes("FD DA 10 06 02 2D"), AutoMeasure.query(Metric.STRESS))
        assertArrayEquals(hexBytes("FD DA 10 06 02 25"), AutoMeasure.querySpO2Type())
        val r = Parser().frame(hexBytes("FD DA 10 07 02 08 03")) as RingEvent.TimingInterval
        assertEquals(Metric.SPO2, AutoMeasure.metricForQuery(r.cmd)); assertEquals("15 min", AutoMeasure.label(r.steps))
        assertEquals("30 min", AutoMeasure.label(6)); assertEquals("Off", AutoMeasure.label(0))
        assertEquals(2, (Parser().frame(hexBytes("FD DA 10 07 02 25 02")) as RingEvent.SpO2Type).type)
    }
    @Test fun onlyStockValuesAllowed() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 12), AutoMeasure.ALLOWED_MINUTES.map { AutoMeasure.steps(it) })
        assertThrows(IllegalArgumentException::class.java) { AutoMeasure.steps(7) }
        assertThrows(IllegalArgumentException::class.java) { AutoMeasure.write(Metric.TEMP, 5) }
    }
    @Test fun writesStayBlockedInProbe() {
        for (c in listOf(6, 7, 8, 39)) assertTrue(ProbeCatalog.isBlocked(1, c))
        assertFalse(ProbeCatalog.isBlocked(2, 37))
    }
}

class TodayPullTest {
    @Test fun todayPullRequestsDayZeroOnly() {
        val f = com.rlong.sleephub.ring.ble.Ring.todayPullFrames()
        assertEquals(8, f.size)
        assertTrue(f.take(4).all { it.size == 8 && it[6].toInt() == 0 && it[7].toInt() == 0 })
        assertArrayEquals(hexBytes("FD DA 10 08 02 11 00 00"), f[2])
        assertEquals(15 * 60_000L, com.rlong.sleephub.ring.ble.Ring.TODAY_PULL_MS)
    }
}
