package com.rlong.sleephub

import com.rlong.sleephub.zeo.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZeoProtocolTest {
    private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun crcCheckValue() { assertEquals(0x29B1, Hmsg.crc16("123456789".toByteArray())) }

    @Test fun commandFrameGolden() {
        // independently computed (python bitwise CRC-16/CCITT-FALSE over bytes 6..)
        assertArrayEquals(hex("48 4d 53 47 c9 4d 02 01 00 01 04 00 06 00 00 00"), Hmsg.build(Hmsg.T.COMMAND, byteArrayOf(6, 0, 0, 0), seqNo = 1))
    }

    @Test fun magicIsHMSG() { assertEquals("HMSG", String(Hmsg.build(0, ByteArray(0), 1).copyOf(4))); assertEquals(1196641608L, ByteBuffer.wrap(Hmsg.MAGIC).order(ByteOrder.LITTLE_ENDIAN).int.toLong()) }

    @Test fun bodySizesMatchOfficial() {
        mapOf(0 to 0, 1 to 4, 2 to 4, 3 to 104, 4 to 4, 5 to 72, 6 to 4, 7 to 1144, 8 to 56, 9 to 16, 10 to 8, 11 to 12, 12 to 4, 13 to 12)
            .forEach { (t, n) -> assertEquals("type $t", n, Hmsg.bodySize(t)) }
    }

    @Test fun assemblerHandlesSplitConcatAndGarbage() {
        val a = Hmsg.build(Hmsg.T.STATE_CHANGE, byteArrayOf(7, 0, 0, 0), 5)
        val b = Hmsg.build(Hmsg.T.ERROR, byteArrayOf(3, 0, 0, 0), 6)
        val stream = byteArrayOf(1, 2, 0x48, 3) + a + b
        val asm = Hmsg.Assembler()
        val got = ArrayList<Hmsg.Frame>()
        stream.toList().chunked(5).forEach { got += asm.feed(it.toByteArray()) }
        assertEquals(2, got.size)
        assertEquals(ZeoMsg.StateChange(7), ZeoParser.parse(got[0]))
        assertEquals("on head", (ZeoParser.parse(got[0]) as ZeoMsg.StateChange).label)
        assertEquals("PIN needed", (ZeoParser.parse(got[1]) as ZeoMsg.Error).label)
    }

    @Test fun badCrcIsRejectedAndResyncs() {
        val bad = Hmsg.build(Hmsg.T.ACK, byteArrayOf(1, 0, 0, 0), 1).also { it[12] = 9 }
        val good = Hmsg.build(Hmsg.T.ACK, byteArrayOf(2, 0, 0, 0), 2)
        var bads = 0
        val f = Hmsg.Assembler { _, _ -> bads++ }.feed(bad + good)
        assertEquals(1, f.size); assertEquals(ZeoMsg.Ack(2), ZeoParser.parse(f[0])); assertTrue(bads >= 1)
    }

    @Test fun wrongLengthForKnownTypeRejected() {
        val f = Hmsg.build(Hmsg.T.STATE, ByteArray(4), 1)  // STATE must be 16
        assertTrue(Hmsg.Assembler().feed(f).isEmpty())
    }

    @Test fun unknownTestTypeIsLoggedNotDropped() {
        val f = Hmsg.Assembler().feed(Hmsg.build(171, ByteArray(10), 1))
        assertEquals(1, f.size); assertTrue(ZeoParser.parse(f[0]) is ZeoMsg.Unknown)
    }

    @Test fun stateReport() {
        val b = le(16).apply { put(0); put(1); put(0); put(1); put(1); put(0); put(1); put(0); put(200.toByte()); put(2); put(0); put(2); putInt(123456) }.array()
        val s = ZeoParser.parse(Hmsg.T.STATE, b) as ZeoMsg.State
        assertTrue(s.btLocked); assertTrue(s.docked); assertTrue(s.onHead); assertFalse(s.demoMode)
        assertEquals(200, s.voltage); assertEquals("charging", s.voltageLabel); assertEquals(2, s.algorithmMode); assertEquals(123456L, s.sensorUseSeconds)
    }

    @Test fun startingMessage() {
        val b = le(104)
        fun str(s: String, n: Int) { val a = ByteArray(n); s.toByteArray().copyInto(a); b.put(a) }
        str("Zeo", 20); b.put(ByteArray(6)); b.putShort(1); b.putShort(2); b.putShort(3)
        b.putInt(0x11); str("ZEOMBL1", 12); str("SN12345", 16); b.putInt(0x02030004)
        b.putInt(0xEF001EE5.toInt()); b.putInt(0x4C98)
        str("assert_fn", 20); b.putInt(42); b.put(1); b.put(1); b.putShort(7)
        val m = ZeoParser.parse(Hmsg.T.STARTING, b.array()) as ZeoMsg.Starting
        assertEquals("Zeo", m.deviceName); assertEquals("ZEOMBL1", m.model); assertEquals("SN12345", m.serial)
        assertEquals(0x02030004L, m.swVersion); assertEquals(0x11L, m.hwVersion); assertEquals("4C:98:EF:00:1E:E5", m.btAddress)
        assertEquals("assert_fn", m.lastAssertFn); assertEquals(42L, m.lastAssertLine); assertTrue(m.queryResponse); assertEquals(7, m.resetCount)
    }

    @Test fun hypnogramNibbleOrderLowFirst() {
        // byte 0x34 → epoch0 = 4 (deep), epoch1 = 3 (light)
        assertEquals(listOf(ZStage.DEEP, ZStage.LIGHT, ZStage.WAKE), ZeoParser.decodeHyp(byteArrayOf(0x34, 0x01), 3))
        val enc = ZeoParser.encodeHyp(listOf(1, 2, 3, 4, 1), 3)
        assertArrayEquals(byteArrayOf(0x21, 0x43, 0x01), enc)
    }

    @Test fun sleepReportLayout() {
        val stages = List(25) { listOf(1, 3, 4, 3, 2)[it % 5] }
        val b = le(1144)
        b.putInt(1000); b.putShort(3); b.putShort(40); b.putShort(200); b.putShort(90); b.putShort(10); b.putShort(15); b.putShort(330); b.putShort(87)
        b.put(1); b.put(1); b.put(150.toByte()); b.put(0)
        b.putInt(9); repeat(5) { b.putShort(it.toShort()) }; b.putShort(2); b.putShort(25); b.putShort(20)
        b.putShort(30); b.putShort(0); b.putInt(5000)
        b.putInt(3); b.putInt(0); b.put(ZeoParser.encodeHyp(listOf(3, 4, 2), 96)); b.putInt(1000)
        b.put(0); b.put(ByteArray(3)); b.putInt(0); b.putShort(4); b.putShort(0)
        b.putInt(1234567); b.put(1); b.put(ByteArray(3))
        b.putInt(stages.size); b.put(ZeoParser.encodeHyp(stages, 960))
        assertEquals(1144, b.position())
        val s = ZeoParser.parse(Hmsg.T.SLEEP, b.array()) as ZeoMsg.Sleep
        assertEquals(87, s.zq); assertEquals(40, s.timeInDeep); assertEquals(200, s.timeInLight); assertEquals(90, s.timeInRem); assertEquals(10, s.timeInWake)
        assertEquals(3, s.awakenings); assertEquals(150, s.voltageBattery); assertEquals(5000L, s.endOfNight); assertEquals(1234567L, s.startOfNight)
        assertTrue(s.valid); assertEquals(listOf(ZStage.LIGHT, ZStage.DEEP, ZStage.REM), s.display)
        assertEquals(25, s.base.size); assertEquals(ZStage.REM, s.currentStage); assertEquals(ZStage.WAKE, s.base[0])
        // full frame round trip through the assembler
        val f = Hmsg.Assembler().feed(Hmsg.build(Hmsg.T.SLEEP, b.array(), 9))
        assertEquals(87, (ZeoParser.parse(f.single()) as ZeoMsg.Sleep).zq)
    }

    @Test fun timeReport() {
        val b = le(12).apply { putInt(1_700_000_000); putInt(250); put(1); put(1); put(4); put(0) }.array()
        val t = ZeoParser.parse(Hmsg.T.TIME_REPORT, b) as ZeoMsg.TimeReport
        assertEquals(1_700_000_000_250L, t.timeMs); assertTrue(t.isOffset); assertTrue(t.negative); assertEquals(4, t.querySeq)
    }

    @Test fun clearWakeWindowMatchesOfficial() {
        val f = Hmsg.clearWakeWindow(nowMs = (Hmsg.EARLIEST_SEC + 100) * 1000)
        val bb = ByteBuffer.wrap(f, 12, 12).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(100, bb.int); assertEquals(0, bb.int); assertEquals(0, bb.int)
    }

    // ---- Safety policy ----
    @Test fun allProbesAreSafe() { ZeoSafety.PROBES.forEach { p -> val f = p.build(); assertNull(p.label, ZeoSafety.check(f[7].toInt() and 0xFF, f.copyOfRange(12, f.size))) } }

    @Test fun dangerousCommandsBlocked() {
        listOf(1, 2, 3, 4, 5, 7, 9, 10, 100).forEach { c -> assertNotNull("cmd $c", ZeoSafety.check(1, byteArrayOf(c.toByte(), 0, 0, 0))) }
        (150..175).filter { it != 163 }.forEach { t -> assertNotNull("type $t", ZeoSafety.check(t, ByteArray(4))) }
        assertNotNull(ZeoSafety.check(173, ByteArray(0)))
        assertNotNull(ZeoSafety.check(8, ByteArray(56)))   // SET_UP_BLUETOOTH
        assertNotNull(ZeoSafety.check(11, ByteArray(12)))  // TIME_REPORT (clock write)
        assertNotNull(ZeoSafety.check(13, ByteArray(12).also { it[4] = 1 })) // real wake window
        assertNotNull(ZeoSafety.check(163, byteArrayOf(5, 0, 0, 0)))
    }

    @Test fun customHexEntry() {
        val (ok, _) = ZeoSafety.fromUserHex("01 06 00 00 00"); assertNotNull(ok); assertEquals(6, ok!![12].toInt())
        val (no, msg) = ZeoSafety.fromUserHex("01 05 00 00 00"); assertNull(no); assertTrue(msg.startsWith("BLOCKED"))
        val (no2, msg2) = ZeoSafety.fromUserHex("AD"); assertNull(no2); assertTrue(msg2.contains("DANGEROUS"))
        val (no3, _) = ZeoSafety.fromUserHex("64"); assertNull(no3)  // type 100 not valid either
        val (nul, _) = ZeoSafety.fromUserHex("00"); assertNotNull(nul)
    }
}
