package com.rlong.sleephub.ring

import com.rlong.sleephub.oxi.OxiReading
import com.rlong.sleephub.ring.data.Decimator
import com.rlong.sleephub.ring.data.Series
import org.junit.Assert.*
import org.junit.Test

class DecimatorTest {
    private val S = 1_000L
    private fun sec(n: Int, f: (Int) -> Float) = Pair(LongArray(n) { it * S }, FloatArray(n) { f(it) })

    @Test fun rawPassthroughWhenSparse() {
        val (t, v) = sec(50) { 90f + it % 7 }
        val r = Decimator.decimate(t, v, 10 * S, 30 * S, cols = 300)
        assertTrue(r.raw)
        val pts = r.runs.flatten()
        assertEquals(23, pts.size)   // 10..30 plus one neighbour each side
        assertEquals(9 * S, pts.first().t); assertEquals(31 * S, pts.last().t)
        pts.forEach { p -> val i = (p.t / S).toInt(); assertEquals(v[i], p.lo); assertEquals(v[i], p.hi) }
    }

    @Test fun minMaxPerColumnCorrect() {
        // 36 000 points (10 h at 1 Hz) into 100 columns: each column = 360 s
        val n = 36_000
        val (t, v) = sec(n) { i -> if (i == 12_345) 70f else if (i == 20_000) 120f else 95f + (i % 3) }
        val r = Decimator.decimate(t, v, 0, (n - 1) * S, cols = 100)
        assertFalse(r.raw)
        assertTrue(r.pointCount <= 102)
        val all = r.runs.flatten()
        assertEquals(70f, all.minOf { it.lo }); assertEquals(120f, all.maxOf { it.hi })
        // column containing the dip has exactly that min and the normal max
        val dip = all.first { it.lo == 70f }; assertEquals(97f, dip.hi)
        // brute-force check every column
        val cols = 100; val span = ((n - 1) * S).toDouble()
        val expect = HashMap<Long, Pair<Float, Float>>()
        for (i in 0 until n) { val c = Math.floor(t[i] / span * cols).toLong(); val e = expect[c]; expect[c] = if (e == null) v[i] to v[i] else minOf(e.first, v[i]) to maxOf(e.second, v[i]) }
        assertEquals(expect.size, all.size)
        all.forEach { p -> val c = Math.floor(p.t / span * cols).toLong(); assertEquals(expect[c]!!.first, p.lo); assertEquals(expect[c]!!.second, p.hi) }
    }

    @Test fun nanGapsPreservedWhenDecimated() {
        val n = 30_000
        val (t, v) = sec(n) { i -> if (i in 10_000 until 12_000) Float.NaN else 60f }
        val r = Decimator.decimate(t, v, 0, (n - 1) * S, cols = 200)
        assertFalse(r.raw); assertEquals(2, r.runs.size)
        assertTrue(r.runs[0].last().t < 10_000 * S); assertTrue(r.runs[1].first().t >= 12_000 * S)
        r.runs.flatten().forEach { assertFalse(it.lo.isNaN()); assertFalse(it.hi.isNaN()) }
    }

    @Test fun nanAndTimeGapsPreservedWhenRaw() {
        val t = longArrayOf(0, 1 * S, 2 * S, 3 * S, 20 * S, 21 * S)
        val v = floatArrayOf(97f, Float.NaN, 96f, 95f, 94f, 93f)
        val r = Decimator.decimate(t, v, 0, 21 * S, cols = 500, maxGap = 5 * S)
        assertTrue(r.raw)
        assertEquals(listOf(1, 2, 2), r.runs.map { it.size })   // NaN breaks after 0; 3→20 s jump breaks again
    }

    @Test fun timeGapsPreservedWhenDecimated() {
        val t = LongArray(4000) { if (it < 2000) it * S else (it + 600) * S }   // 10 min hole
        val v = FloatArray(4000) { 80f }
        val r = Decimator.decimate(t, v, 0, t.last(), cols = 100, maxGap = 5 * S)
        assertFalse(r.raw); assertEquals(2, r.runs.size)
    }

    @Test fun zoomedWindowReachesFullPerSecondData() {
        val n = 40_000
        val (t, v) = sec(n) { i -> (i % 100).toFloat() }
        val r = Decimator.decimate(t, v, 25_000 * S, 25_059 * S, cols = 900)   // 60 s window
        assertTrue(r.raw); val pts = r.runs.flatten()
        assertEquals(62, pts.size)
        for (k in 1..60) assertEquals((25_000 + k - 1) * S, pts[k].t)
    }

    @Test fun emptyAndOutOfRange() {
        assertTrue(Decimator.decimate(LongArray(0), FloatArray(0), 0, 10, 10).runs.isEmpty())
        val (t, v) = sec(10) { 1f }
        assertTrue(Decimator.decimate(t, v, 0, 10 * S, 0).runs.isEmpty())
    }

    @Test fun seriesNullsBecomeGaps() {
        // invalid oximeter values arrive as null from OxiDb.range (pulse outside 25–250, SpO2 outside 1–100) → NaN gap
        val l = listOf(OxiReading(3 * S, 95, 60), OxiReading(1 * S, 96, null), OxiReading(2 * S, null, 61))
        val sp = Series.of(l, { it.time }, { it.spo2 }); val pr = Series.of(l, { it.time }, { it.pulse })
        assertArrayEquals(longArrayOf(1 * S, 2 * S, 3 * S), sp.times)
        assertTrue(sp.vals[1].isNaN()); assertTrue(pr.vals[0].isNaN())
        assertEquals(95f, sp.min); assertEquals(96f, sp.max)
        assertEquals(2, Decimator.decimate(sp.times, sp.vals, 0, 3 * S, 100).runs.size)
    }
}
