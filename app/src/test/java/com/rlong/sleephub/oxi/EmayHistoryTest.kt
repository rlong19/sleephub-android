package com.rlong.sleephub.oxi

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EmayHistoryTest {
    private fun b(vararg v: Int) = EmayProtocol.bytes(*v)
    private fun withCks(vararg v: Int) = EmayProtocol.command(b(*v))

    /** Build a 24-byte ED 01 packet per doc §5 from 27 values (null = invalid). Encodes high bits into [7]/[8]. */
    private fun packet(type: Int, item: Int, seq: Int, vals: List<Int?>): ByteArray {
        val raw = IntArray(24); raw[0] = 0xED; raw[1] = 1; raw[2] = type; raw[3] = 1; raw[4] = item; raw[5] = seq and 0x7F; raw[6] = seq shr 7
        raw[9] = vals[0] ?: 255
        var prev = raw[9]; val nibs = ArrayList<Int>()
        for (v in vals.drop(1)) { if (v == null) { nibs += 7; continue }; val d = v - prev; require(kotlin.math.abs(d) < 7); nibs += (if (d < 0) 8 else 0) or kotlin.math.abs(d); prev = v }
        for (k in 0 until 13) raw[10 + k] = (nibs[2 * k] shl 4) or nibs[2 * k + 1]
        for (i in 0 until 7) { if (raw[9 + i] >= 128) { raw[7] = raw[7] or (1 shl i); raw[9 + i] -= 128 }; if (raw[16 + i] >= 128) { raw[8] = raw[8] or (1 shl i); raw[16 + i] -= 128 } }
        val out = ByteArray(24) { raw[it].toByte() }; out[23] = EmayProtocol.checksum(out, 23).toByte(); return out
    }

    @Test fun decodesDeltas() {
        val vals: List<Int?> = listOf(97, 97, 96, 95, null, 93, 94, 97, 100, 99, 98, 98, 92, 92, 93, 94, 95, 96, 97, 97, 97, 97, 96, 96, 95, 95, 95)
        val p = packet(1, 3, 0, vals)
        assertTrue(EmayHistory.packetOk(p, 1, 3, 0))
        assertFalse(EmayHistory.packetOk(p, 2, 3, 0)); assertFalse(EmayHistory.packetOk(p, 1, 4, 0)); assertFalse(EmayHistory.packetOk(p, 1, 3, 1))
        assertEquals(vals, EmayHistory.decode(p))
        val bad = p.copyOf(); bad[12] = (bad[12] + 1).toByte(); assertFalse(EmayHistory.packetOk(bad, 1, 3, 0))
    }
    @Test fun decodesHighBitPulse() {
        val vals: List<Int?> = (0 until 27).map { 140 + (it % 5) }
        val p = packet(2, 1, 130, vals)
        assertTrue(EmayHistory.packetOk(p, 2, 1, 130))
        assertEquals(vals, EmayHistory.decode(p))
    }
    @Test fun trimsAndMerges() {
        val a = packet(1, 1, 0, List(27) { 96 }); val c = packet(1, 1, 1, List(27) { 96 })
        assertEquals(30, EmayHistory.decodeAll(listOf(a, c), 30).size)
        assertEquals(2, EmayHistory.packets(30)); assertEquals(48, EmayHistory.Segment(1, 1, 0, 30, true, false).expectedBytes)
        val r = EmayHistory.readings(1000, listOf(96, null), listOf(60, 61)); assertEquals(2000L, r[1].time); assertNull(r[1].spo2)
    }
    @Test fun segmentHeader() {
        // EC FL user item YY MM DD hh mm ss L0..L3 + pad to 20 + cks; L = 3600 = 0x0E10 → lo7 0x10, hi7 0x1C
        val f = withCks(0xEC, 0x40, 1, 5, 26, 10, 21, 23, 15, 30, 0x10, 0x1C, 0, 0, 0, 0, 0, 0, 0, 0)
        val s = EmayHistory.segment(f, TimeZone.getTimeZone("UTC"))!!
        assertEquals(5, s.item); assertEquals(3600, s.length); assertTrue(s.last); assertFalse(s.clockUnset)
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 9, 21, 23, 15, 30) }
        assertEquals(c.timeInMillis, s.start)
    }
    @Test fun summary() { val s = EmayHistory.summary(withCks(0xE0, 0x06, 3, 0, 1, 0, 0, 1, 0, 0, 5, 0, 0, 0))!!; assertEquals(3, s.total); assertEquals(1, s.notUploaded); assertEquals(128L, s.storage); assertEquals(5L, s.free) }

    @Test fun battery() {
        assertEquals(85, EmayProtocol.parseBattery(withCks(0xF6, 85)))
        assertEquals(5, EmayProtocol.parseBattery(withCks(0xF6, 0x85)))
        assertNull(EmayProtocol.parseBattery(b(0xF6, 85, 0)))
        assertNull(EmayProtocol.parseBattery(withCks(0x86, 85)))
    }
    @Test fun pulseHighBit() {
        val r = EmayProtocol.parse(withCks(0xEB, 0x01, 0x07, 10, 98, 0x7F, 0x00), 0)!!
        assertEquals(138, r.pulse); assertEquals(98, r.spo2)
        assertEquals(72, EmayProtocol.parse(withCks(0xEB, 0x01, 0x04, 72, 98, 0x7F, 0x00), 0)!!.pulse)
    }
    @Test fun deleteAndE2Blocked() {
        assertEquals("9D7F7F7F7F000019", b(0x9D, 0x7F, 0x7F, 0x7F, 0x7F, 0, 0, 0x19).joinToString("") { "%02X".format(it) })
        assertTrue(EmayProtocol.guard(b(0x9D, 0x7F, 0x7F, 0x7F, 0x7F, 0, 0, 0x19))!!.startsWith("HARD BLOCK"))
        assertTrue(EmayProtocol.guard(b(0x9D, 0x7F, 0x7F, 0x7F, 0x7F, 0, 0, 0x19), clockAllowed = true)!!.startsWith("HARD BLOCK"))
        assertTrue(EmayProtocol.guard(withCks(0xE2, 0xA9, 0x2F))!!.startsWith("HARD BLOCK"))
        assertTrue(EmayProtocol.guard(withCks(0xE2, 0xA1))!!.startsWith("HARD BLOCK"))
        assertNull(EmayProtocol.guard(EmayProtocol.download(1, 5, 0)))
        assertNull(EmayProtocol.guard(EmayProtocol.download(2, 5, 200)))
        assertNull(EmayProtocol.guard(EmayProtocol.abort(1, 5)))
        assertArrayEquals(b(0x9D, 0x01, 0x01, 0x01, 0x05, 0, 0, (0x9D + 1 + 1 + 1 + 5) and 0x7F), EmayProtocol.download(1, 5, 0))
        assertNull(EmayProtocol.guard(withCks(0x90, 0x06))); assertNull(EmayProtocol.guard(withCks(0x9C, 0x01)))
        val clock = EmayProtocol.setClock(Calendar.getInstance())
        assertNotNull(EmayProtocol.guard(clock)); assertNull(EmayProtocol.guard(clock, clockAllowed = true))
        assertNotNull(EmayProtocol.guard(withCks(0x9D, 0x03, 1, 1, 1, 0, 0)))
    }
}
