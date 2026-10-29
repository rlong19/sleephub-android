package com.rlong.sleephub.oxi

import org.junit.Assert.*
import org.junit.Test

class OxiSeriesTest {
    private val now = 1_000_000L
    private fun r(s: Int, sp: Int?, pr: Int?) = OxiReading(now - s * 1000L, sp, pr)
    @Test fun fingerOffBreaksLineNoZeros() {
        val rs = listOf(r(5, 97, 60), r(4, 96, 61), r(3, null, null), r(2, 95, 62), r(1, 95, 63))
        val segs = OxiSeries.segments(rs, now, 60_000, { it.spo2 })
        assertEquals(2, segs.size); assertEquals(2, segs[0].size); assertEquals(2, segs[1].size)
        assertTrue(segs.flatten().none { it.second == 0.0 })
    }
    @Test fun timeGapBreaksLine() {
        val segs = OxiSeries.segments(listOf(r(20, 97, 60), r(19, 97, 60), r(5, 96, 60)), now, 60_000, { it.pulse })
        assertEquals(2, segs.size)
    }
    @Test fun windowFilters() { assertEquals(1, OxiSeries.segments(listOf(r(400, 97, 60), r(1, 96, 60)), now, 60_000, { it.spo2 }).flatten().size) }
    @Test fun pulseAutoscaleMinWidth() {
        val (lo, hi) = OxiSeries.pulseRange(listOf(listOf(0L to 60.0, 1L to 62.0)))
        assertTrue(hi - lo >= 20); assertTrue(lo <= 60 && hi >= 62)
        assertEquals(50.0 to 100.0, OxiSeries.pulseRange(emptyList()))
    }
}
