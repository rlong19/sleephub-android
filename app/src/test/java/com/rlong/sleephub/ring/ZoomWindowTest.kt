package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.data.ZoomWindow
import org.junit.Assert.*
import org.junit.Test

class ZoomWindowTest {
    private val H = 3_600_000L
    private fun day() = ZoomWindow(0L, 24 * H)

    @Test fun startsAtFullDayAndResets() {
        val z = day(); assertFalse(z.zoomed); assertEquals(0L, z.t0); assertEquals(24 * H, z.t1)
        z.zoom(4.0, 12 * H); assertTrue(z.zoomed); assertEquals(6 * H, z.span)
        z.reset(); assertFalse(z.zoomed); assertEquals(0L, z.t0); assertEquals(24 * H, z.t1)
    }
    @Test fun zoomKeepsFocusFixed() {
        val z = day(); z.zoom(2.0, 6 * H)   // focus at 25%
        assertEquals(12 * H, z.span); assertEquals(3 * H, z.t0); assertEquals(15 * H, z.t1)
    }
    @Test fun minWindowIs60s() {
        val z = day(); repeat(50) { z.zoom(10.0, 3 * H) }
        assertEquals(60_000L, z.span); assertTrue(3 * H in z.t0..z.t1)
    }
    @Test fun cannotZoomOutPastFullDay() {
        val z = day(); z.zoom(4.0, 1 * H); z.zoom(0.01, 1 * H)
        assertEquals(0L, z.t0); assertEquals(24 * H, z.t1); assertFalse(z.zoomed)
    }
    @Test fun clampsAtEdges() {
        val z = day(); z.zoom(6.0, 0L)
        assertEquals(0L, z.t0); assertEquals(4 * H, z.t1)
        z.pan(-10 * H); assertEquals(0L, z.t0); assertEquals(4 * H, z.span)
        z.pan(100 * H); assertEquals(24 * H, z.t1); assertEquals(20 * H, z.t0)
        z.pan(-H); assertEquals(19 * H, z.t0)
    }
    @Test fun setClampsSpanAndRange() {
        val z = day(); z.set(5 * H, 5 * H + 1000); assertEquals(60_000L, z.span)
        z.set(-5 * H, 30 * H); assertEquals(0L, z.t0); assertEquals(24 * H, z.t1)
        val tiny = ZoomWindow(0L, 30_000L); tiny.zoom(10.0, 15_000L); assertEquals(30_000L, tiny.span)
    }
}

class HistoryZoomTest {
    @Test fun samePageKeepsZoomNewPageResets() {
        val H = 3_600_000L
        val z = com.rlong.sleephub.ring.data.HistoryZoom.forPage(0L, 24 * H); z.zoom(8.0, 3 * H)
        val again = com.rlong.sleephub.ring.data.HistoryZoom.forPage(0L, 24 * H)
        assertSame(z, again); assertEquals(3 * H, again.span); assertTrue(again.zoomed)
        val next = com.rlong.sleephub.ring.data.HistoryZoom.forPage(24 * H, 48 * H)
        assertFalse(next.zoomed); assertEquals(24 * H, next.full0)
    }
}

class HistoryZoomRefreshTest {
    @Test fun partialPageGrowingKeepsZoom() {
        val H = 3_600_000L
        val z = com.rlong.sleephub.ring.data.HistoryZoom.forPage(100 * H, 105 * H); z.zoom(10.0, 102 * H)
        val (a, b) = z.t0 to z.t1
        val again = com.rlong.sleephub.ring.data.HistoryZoom.forPage(100 * H, 106 * H)   // Today (partial) refreshed later
        assertTrue(again.zoomed); assertEquals(a, again.t0); assertEquals(b, again.t1); assertEquals(106 * H, again.full1)
    }
}
