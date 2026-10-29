package com.rlong.sleephub.oxi

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class EmayProtocolTest {
    private fun b(vararg v: Int) = EmayProtocol.bytes(*v)
    private fun frame(pr: Int, sp: Int): ByteArray { val p = b(0xEB, 0x01, 0x05, pr, sp, 0x7F, 0x00); return p + EmayProtocol.checksum(p).toByte() }

    @Test fun commandsMatchSdk() {
        assertArrayEquals(b(0x89, 0x09), EmayProtocol.command(EmayProtocol.HELLO))
        assertArrayEquals(b(0x8E, 0x05, 0x13), EmayProtocol.command(EmayProtocol.DEVICE_STATE))
        assertArrayEquals(b(0x9B, 0x01, 0x1C), EmayProtocol.command(EmayProtocol.START_REALTIME))
        assertArrayEquals(b(0x9B, 0x7F, 0x1A), EmayProtocol.command(EmayProtocol.STOP_REALTIME))
        assertArrayEquals(b(0x86, 0x06), EmayProtocol.command(EmayProtocol.GET_BATTERY))
        assertArrayEquals(b(0x9A, 0x1A), EmayProtocol.command(EmayProtocol.HEARTBEAT))
        assertEquals(4, EmayProtocol.START_SEQUENCE.size)
    }
    @Test fun checksumUses7F() { assertEquals((0xEB + 1 + 5 + 72 + 97 + 0x7F) and 0x7F, EmayProtocol.checksum(b(0xEB, 1, 5, 72, 97, 0x7F, 0))) }
    @Test fun parsesValid() { val r = EmayProtocol.parse(frame(72, 97), 1000)!!; assertEquals(97, r.spo2); assertEquals(72, r.pulse); assertEquals(1000L, r.time); assertTrue(r.fingerOn) }
    @Test fun fingerOff() { val r = EmayProtocol.parse(frame(0xFF, 0xFF))!!; assertNull(r.spo2); assertNull(r.pulse); assertFalse(r.fingerOn)
        assertNull(EmayProtocol.parse(frame(0, 0))!!.spo2) }
    @Test fun rejectsBad() {
        val f = frame(72, 97); f[7] = (f[7] + 1).toByte(); assertNull(EmayProtocol.parse(f))
        assertNull(EmayProtocol.parse(frame(72, 97).copyOf(7)))
        EmayProtocol.parse(frame(10, 97))!!.let { assertNull(it.pulse); assertEquals(97, it.spo2) }  // implausible pulse -> null (gap), SpO2 kept
        assertNull(EmayProtocol.parse(b(0xEB, 0x02, 0x05, 72, 97, 0x7F, 0x00, 0)))
        assertNull(EmayProtocol.parse(frame(72, 101))!!.spo2)     // >100 % -> null
        val g = frame(72, 97); g[0] = 0xEA.toByte(); assertNull(EmayProtocol.parse(g))
    }
    @Test fun whitelistBlocksOthers() { assertNull(EmayProtocol.guard(b(0x9A, 0x1A))); assertNotNull(EmayProtocol.guard(b(0x99, 0x19))) }

    @Test fun csvSdkFormat() {
        val tz = TimeZone.getTimeZone("America/Los_Angeles")
        val r = OxiCsv.parse("Date,Time,SpO2(%),PR(bpm)\n10/20/2026,11:00:00 PM,97,62\n10/20/2026,11:00:01 PM,,\n10/20/2026,11:00:02 PM,95,64\n", tz)
        assertEquals(3, r.readings.size); assertEquals(97, r.readings[0].spo2); assertNull(r.readings[1].spo2); assertEquals(64, r.readings[2].pulse)
        assertEquals(1000L, r.readings[1].time - r.readings[0].time)
    }
    @Test fun csvFlexible() {
        val r = OxiCsv.parse("Time;Pulse;SpO2\n2026-10-20 23:00:00;60;96\n2026-10-20 23:00:01;61;95%\n", TimeZone.getTimeZone("UTC"))
        assertEquals(96, r.readings[0].spo2); assertEquals(61, r.readings[1].pulse)
    }
    @Test fun odi3() {
        val l = (0 until 600).map { s -> OxiReading(s * 1000L, if (s in 300..320) 92 else 97, 60) }
        val e = Odi.find(l); assertEquals(1, e.size); assertEquals(92, e[0].nadir)
        assertEquals(0, Odi.find((0 until 600).map { OxiReading(it * 1000L, 97, 60) }).size)
    }
}
