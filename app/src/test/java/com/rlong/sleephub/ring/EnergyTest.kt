package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.data.Vitals
import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class EnergyTest {
    private val tz = TimeZone.getTimeZone("America/Los_Angeles")
    private val p = Parser(tz) { 1_760_900_000_000L }

    @Test fun commandsMatchSdk() {
        assertArrayEquals(hexBytes("FD DA 10 08 07 0E 02 03"), Cmd.timingPage(Cmd.ENERGY_TABLE, 2, 3))   // b1.y.b
        assertArrayEquals(hexBytes("FD DA 10 06 07 0C"), Cmd.currentEnergy())                             // b1.y.a
        assertEquals(4, Cmd.TIMING[Cmd.ENERGY_TABLE])
    }

    @Test fun pageDecodesU16LeTenthsAndFiltersInvalid() {
        // 0x0258 = 600 → 60.0, 0xFFFF invalid, 0 placeholder, 0x03E7 = 999 → 99.9
        val e = p.frame(Frame.build(7, 14, byteArrayOf(1, 0, 0x58, 0x02, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0xE7.toByte(), 0x03))) as RingEvent.TimingPage
        assertEquals(Cmd.ENERGY_TABLE, e.cmd); assertEquals(Metric.ENERGY, e.metric); assertEquals(1, e.day); assertEquals(0, e.page)
        assertEquals(listOf(60.0, null, null, 99.9), e.values)
    }

    @Test fun collectorFollowsFourPagesOnGroup7() {
        val c = TimingCollector(p)
        for (pg in 0 until 3) {
            val (next, done) = c.onPage(RingEvent.TimingPage(Cmd.ENERGY_TABLE, Metric.ENERGY, 3, pg, List(72) { 50.0 }))
            assertArrayEquals(Frame.build(7, 14, byteArrayOf(3, (pg + 1).toByte())), next); assertNull(done)
        }
        val done = c.onPage(RingEvent.TimingPage(Cmd.ENERGY_TABLE, Metric.ENERGY, 3, 3, List(72) { 40.0 })).second!!
        assertEquals(288, done.size); assertEquals(p.dayStart(3) + 287 * 300_000L, done.last().time); assertEquals("timing", done[0].source)
    }

    @Test fun currentValueAndVitalsMembership() {
        assertEquals(72.5, (p.frame(Frame.build(7, 12, byteArrayOf(0xD5.toByte(), 0x02))) as RingEvent.Live).value, 0.0)
        assertNull(p.frame(Frame.build(7, 12, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))))
        assertNull(p.frame(Frame.build(7, 12, byteArrayOf(0, 0))))
        assertEquals(Metric.ENERGY, Vitals.METRICS.last())
        assertEquals("Energy (physical strength)", Metric.ENERGY.label)
    }
}

class SupportTest {
    @Test fun gomoreRepliesAndVerdicts() {
        val p = Parser()
        assertEquals(RingEvent.GomoreSupport(true, 2), p.frame(hexBytes("FD DA 10 08 07 00 01 02")))
        assertEquals(RingEvent.GomoreKey(false), p.frame(hexBytes("FD DA 10 07 07 01 00")))
        assertNull(Support.energy(true, false, false, false))
        assertTrue(Support.energy(false, false, null, null)!!.contains("not supported"))
        assertTrue(Support.energy(false, true, false, null)!!.contains("licence key"))
        assertTrue(Support.energy(false, true, true, false)!!.contains("not answered"))
        assertNull(Support.energy(false, null, null, null))
        assertTrue(Support.spo2(0)!!.contains("not supported")); assertTrue(Support.spo2(1)!!.contains("only during sleep")); assertNull(Support.spo2(2))
    }
}
