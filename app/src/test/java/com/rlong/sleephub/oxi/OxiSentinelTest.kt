package com.rlong.sleephub.oxi

import org.junit.Assert.*
import org.junit.Test

class OxiSentinelTest {
    private fun frame(fl: Int, pr: Int, sp: Int): ByteArray {
        val b = byteArrayOf(0xEB.toByte(), 0x01, fl.toByte(), pr.toByte(), sp.toByte(), 0, 0, 0)
        b[7] = EmayProtocol.checksum(b, 7).toByte(); return b
    }

    @Test fun fingerOffPulse255IsNull() {
        val r = EmayProtocol.parse(frame(0x07, 0x7F, 97), 1000L)!!   // 7F + 128 flag = 255
        assertNull(r.pulse); assertEquals(97, r.spo2)
        assertNull(EmayProtocol.parse(frame(0x05, 0xFF, 0xFF), 1L)!!.pulse)
        assertNull(EmayProtocol.parse(frame(0x05, 0x00, 0x00), 1L)!!.spo2)
    }
    @Test fun normalValuesPass() {
        val r = EmayProtocol.parse(frame(0x05, 62, 96), 1L)!!
        assertEquals(62, r.pulse); assertEquals(96, r.spo2)
        assertEquals(130, EmayProtocol.parse(frame(0x07, 2, 95), 1L)!!.pulse)
    }
    @Test fun validRanges() {
        listOf(0, 24, 251, 255, 300).forEach { assertNull(OxiValid.pulse(it)) }
        listOf(25, 60, 250).forEach { assertEquals(it, OxiValid.pulse(it)) }
        listOf(0, 101, 127, 255).forEach { assertNull(OxiValid.spo2(it)) }
        listOf(1, 88, 100).forEach { assertEquals(it, OxiValid.spo2(it)) }
        assertNull(OxiValid.pulse(null))
    }
    @Test fun sanitizedReading() {
        val r = OxiReading(5L, 127, 255).sanitized()
        assertNull(r.spo2); assertNull(r.pulse); assertFalse(r.fingerOn)
        val ok = OxiReading(5L, 97, 61); assertSame(ok, ok.sanitized())
    }
    @Test fun historyReadingsDropSentinels() {
        val l = EmayHistory.readings(0L, listOf(97, 127, 0), listOf(255, 60, 0))
        assertEquals(listOf(null, 60, null), l.map { it.pulse }); assertEquals(listOf(97, null, null), l.map { it.spo2 })
    }
}
