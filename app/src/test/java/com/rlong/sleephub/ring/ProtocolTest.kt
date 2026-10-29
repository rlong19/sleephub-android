package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ProtocolTest {
    private val tz = TimeZone.getTimeZone("America/Los_Angeles")
    private val p = Parser(tz) { 1_700_000_000_000L }

    @Test fun frameBuildAndLength() {
        val f = Frame.build(1, 9, byteArrayOf(1))
        assertEquals(7, f.size)
        assertEquals(0xFD.toByte(), f[0]); assertEquals(0xDA.toByte(), f[1])
        assertEquals(0x10.toByte(), f[2]); assertEquals(7, f[3].toInt() and 0xFF)
        assertEquals(1, f[4].toInt()); assertEquals(9, f[5].toInt()); assertEquals(1, f[6].toInt())
        val big = Frame.build(2, 15, ByteArray(300))
        assertEquals(306, big.size)
        assertEquals(0x11.toByte(), big[2])
        assertEquals(306 and 0xFF, big[3].toInt() and 0xFF)
        assertEquals(306, Frame.length(big[2], big[3]))
    }

    @Test fun assemblerReassemblesChunks() {
        val f = Frame.build(2, 9, ByteArray(40) { it.toByte() })
        val a = Frame.Assembler()
        assertNull(a.feed(f.copyOfRange(0, 20)))
        val done = a.feed(f.copyOfRange(20, f.size))
        assertNotNull(done); assertArrayEquals(f, done)
        assertNull(a.feed(byteArrayOf(1, 2, 3)))
    }

    @Test fun syncTimeRoundTrip() {
        val f = Cmd.syncTime(1_700_000_000_000L, tz)
        assertEquals(11, f.size)
        assertEquals(1, f[4].toInt()); assertEquals(1, f[5].toInt()); assertEquals(8, f[10].toInt())
        val sec = le32(f, 6)
        assertEquals(RingTime.toDevice(1_700_000_000_000L, tz), sec)
        assertEquals(1_700_000_000_000L, RingTime.fromDevice(sec, tz))
    }

    @Test fun measureCommandsMatchSdk() {
        // Exact bytes produced by b1.q.c for the same (group,cmd,payload)
        assertArrayEquals(hexBytes("FD DA 10 07 01 09 01"), Cmd.measureHeartRate(true))
        assertArrayEquals(hexBytes("FD DA 10 07 01 0B 00"), Cmd.measureSpO2(false))
        assertArrayEquals(hexBytes("FD DA 10 07 01 0A 01"), Cmd.measureHrv(true))
        assertArrayEquals(hexBytes("FD DA 10 07 01 0E 01"), Cmd.measureStress(true))
        assertArrayEquals(hexBytes("FD DA 10 07 01 20 01"), Cmd.measureTemp(true))
        assertArrayEquals(hexBytes("FD DA 10 06 02 09"), Cmd.historyHeartRate())
        assertArrayEquals(hexBytes("FD DA 10 07 02 0E 00"), Cmd.historySleep(0))
        assertArrayEquals(hexBytes("FD DA 10 08 02 0F 00 00"), Cmd.timingHeartRatePage(0, 0))
        assertArrayEquals(hexBytes("FD DA 10 06 03 03"), Cmd.firmwareVersion())
        assertArrayEquals(hexBytes("FD DA 10 0B 01 00 AA BB CC DD EE"), Cmd.userInfo(0xAA, 0xBB, 0xCC, 0xDD, 0xEE))
    }

    @Test fun parseLiveAndHistory() {
        val live = p.frame(Frame.build(1, 9, byteArrayOf(72))) as RingEvent.Live
        assertEquals(Metric.HEART_RATE, live.metric); assertEquals(72.0, live.value, 0.0)
        val ts = RingTime.toDevice(1_700_000_000_000L, tz)
        val rec = byteArrayOf(1, 68, ts.toByte(), (ts shr 8).toByte(), (ts shr 16).toByte(), (ts shr 24).toByte())
        val hist = p.frame(Frame.build(2, 9, rec)) as RingEvent.History
        assertEquals(1, hist.samples.size)
        assertEquals(68.0, hist.samples[0].value, 0.0)
        assertEquals(1_700_000_000_000L, hist.samples[0].time)
    }

    @Test fun parseSleepStages() {
        val raw = byteArrayOf(0, 0, 23, 0, 1, 23, 30, 2, 1, 0, 3, 3, 0, 1, 5, 0, 0, 7, 0)
        val s = p.sleep(raw) as RingEvent.Sleep
        assertEquals(0, s.day)
        assertTrue(s.segments.size >= 4)
        assertEquals(0, s.segments.first().stage)
        assertTrue(s.segments.any { it.stage == 2 })
        assertTrue(s.segments.any { it.stage == 3 })
        val totalMin = s.segments.sumOf { it.minutes }
        assertTrue("total=$totalMin", totalMin in 400..600)
    }

    @Test fun timingCollectorPages() {
        val tc = TimingCollector(p)
        val p0 = RingEvent.TimingPage(15, Metric.HEART_RATE, 0, 0, List(10) { 60.0 + it })
        val (next, done0) = tc.onPage(p0)
        assertNotNull(next); assertNull(done0)
        assertEquals(15, next!![5].toInt() and 0xFF); assertEquals(1, next[7].toInt())
        val p1 = RingEvent.TimingPage(15, Metric.HEART_RATE, 0, 1, List(10) { 70.0 + it })
        val (n2, done) = tc.onPage(p1)
        assertNull(n2); assertNotNull(done); assertEquals(20, done!!.size)
        assertEquals(Metric.HEART_RATE, done[0].metric)
    }

    @Test fun currentStepsAndHrChar() {
        val steps = byteArrayOf(0xE5.toByte(), 0x13, 0, 0x50, 0x15, 0, 0xD2.toByte(), 0, 0)
        val cs = p.currentSteps(steps) as RingEvent.CurrentSteps
        assertEquals(5093, cs.steps); assertEquals(5456, cs.distance); assertEquals(210, cs.calories)
        val hr = p.heartRateChar(byteArrayOf(0, 77, 0x00, 0x04)) as RingEvent.Live
        assertEquals(77.0, hr.value, 0.0)
        assertNull(p.heartRateChar(byteArrayOf(0, 77, 0, 0)))
    }

    @Test fun sdkMagicBytes() {
        // CRPScanRecordParser DEVICE_FIRST/SECOND = -38,-3; SETTING = -48,-3
        assertEquals((-38).toByte(), 0xDA.toByte())
        assertEquals((-3).toByte(), 0xFD.toByte())
        assertEquals((-48).toByte(), 0xD0.toByte())
        assertEquals((-3).toByte(), Frame.H0) // frame header is FD DA (opposite endian of scan magic DA FD)
        assertEquals((-38).toByte(), Frame.H1)
    }

    @Test fun batteryChargingEncoding() {
        // Real log: 2A19 = 0x66 (102) while on charger → Charging 2%
        val a = BatteryReading.fromRaw(0x66)
        assertTrue(a.charging); assertEquals(2, a.percent)
        assertEquals("Charging 2%", a.label)
        val b = BatteryReading.fromRaw(40)
        assertFalse(b.charging); assertEquals(40, b.percent)
        assertEquals("40%", b.label)
        val c = BatteryReading.fromRaw(160)
        assertTrue(c.charging); assertEquals(60, c.percent)
        val d = BatteryReading.fromRaw(100)
        assertFalse(d.charging); assertEquals(100, d.percent)
    }

    @Test fun describeFrameNamesMeasure() {
        assertEquals("startMeasureHR", describeFrame(Cmd.measureHeartRate(true)))
        assertEquals("stopMeasureHR", describeFrame(Cmd.measureHeartRate(false)))
        assertEquals("startMeasureSpO2", describeFrame(Cmd.measureSpO2(true)))
        // Real ring reply FD DA 10 07 01 09 4F → result 79 bpm (not stop)
        assertEquals("MeasureHR_result=79", describeFrame(Frame.build(1, 9, byteArrayOf(0x4F))))
        assertEquals("MeasureSpO2_failed", describeFrame(Frame.build(1, 11, byteArrayOf(0xFF.toByte()))))
        assertEquals("realtimeBattery", describeFrame(Cmd.realtimeBattery()))
        assertEquals("syncTime", describeFrame(Cmd.syncTime(1_700_000_000_000L, tz)))
    }

    @Test fun liveMeasureResultAndFail() {
        val ok = p.frame(Frame.build(1, 9, byteArrayOf(0x4F))) as RingEvent.Live
        assertEquals(79.0, ok.value, 0.0)
        val fail = p.frame(Frame.build(1, 11, byteArrayOf(0xFF.toByte()))) as RingEvent.MeasureFailed
        assertEquals(Metric.SPO2, fail.metric)
        assertEquals(Valid.FAIL_MSG, fail.reason)
    }

    @Test fun allMeasuresFailOnAllOnes() {
        for ((cmd, m) in listOf(9 to Metric.HEART_RATE, 10 to Metric.HRV, 11 to Metric.SPO2, 14 to Metric.STRESS)) {
            val f1 = p.frame(Frame.build(1, cmd, byteArrayOf(0xFF.toByte()))) as RingEvent.MeasureFailed
            assertEquals(m, f1.metric); assertEquals("Measurement failed - wear the ring snugly", f1.reason)
            val f2 = p.frame(Frame.build(1, cmd, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))) as RingEvent.MeasureFailed
            assertEquals(m, f2.metric)
        }
        // implausible live values
        assertTrue(p.frame(Frame.build(1, 11, byteArrayOf(120))) is RingEvent.MeasureFailed)
        assertTrue(p.frame(Frame.build(1, 9, byteArrayOf(5))) is RingEvent.MeasureFailed)
    }

    @Test fun tempLiveValidation() {
        val ok = p.frame(Frame.build(1, 32, byteArrayOf(0x64, 0x01))) as RingEvent.Live   // 356 → 35.6 °C
        assertEquals(Metric.TEMP, ok.metric); assertEquals(35.6, ok.value, 1e-9)
        for (raw in listOf(0xFFFF, 0xFF00, 0xFFFE, 0, 199, 451, 6553)) {
            val e = p.frame(Frame.build(1, 32, byteArrayOf((raw and 0xFF).toByte(), (raw shr 8).toByte())))
            assertTrue("raw=$raw", e is RingEvent.MeasureFailed)
            assertEquals(Valid.FAIL_MSG, (e as RingEvent.MeasureFailed).reason)
        }
        assertTrue(p.frame(Frame.build(1, 32, byteArrayOf(0xFF.toByte()))) is RingEvent.MeasureFailed)
        assertEquals(25.5, (p.frame(Frame.build(1, 32, byteArrayOf(0xFF.toByte(), 0))) as RingEvent.Live).value, 1e-9)  // 0x00FF is a real 25.5 °C
        assertEquals("MeasureTemp_failed", describeFrame(Frame.build(1, 32, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))))
        assertEquals("MeasureTemp_result=35.6", describeFrame(Frame.build(1, 32, byteArrayOf(0x64, 0x01))))
        assertEquals("startMeasureTemp", describeFrame(Cmd.measureTemp(true)))
    }

    @Test fun historyAndTimingDropAllOnes() {
        val ts = RingTime.toDevice(1_700_000_000_000L, tz)
        fun le32b(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val hr = byteArrayOf(2) + byteArrayOf(0xFF.toByte()) + le32b(ts) + byteArrayOf(70) + le32b(ts + 60)
        val h = p.frame(Frame.build(2, 9, hr)) as RingEvent.History
        assertEquals(1, h.samples.size); assertEquals(70.0, h.samples[0].value, 0.0)
        val hrv = byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + le32b(ts)
        assertEquals(0, (p.frame(Frame.build(2, 10, hrv)) as RingEvent.History).samples.size)
        val tp = p.frame(Frame.build(2, 22, byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0x64, 0x01))) as RingEvent.TimingPage
        assertNull(tp.values[0]); assertEquals(35.6, tp.values[1]!!, 1e-9)
        assertNull(p.heartRateChar(byteArrayOf(0, 0xFF.toByte())))
        assertNull(p.heartRateChar(byteArrayOf(0, 0)))
    }

    @Test fun fdd1LiveSteps() {
        // Real notify: 0A 00 00 06 00 00 01 00 00 → steps=10 dist=6 kcal=1
        val ev = p.currentSteps(hexBytes("0A 00 00 06 00 00 01 00 00")) as RingEvent.CurrentSteps
        assertEquals(10, ev.steps); assertEquals(6, ev.distance); assertEquals(1, ev.calories)
    }

    @Test fun measureTimeoutsMatchPolicy() {
        assertEquals(45_000L, measureTimeoutMs(Metric.HEART_RATE))
        assertEquals(90_000L, measureTimeoutMs(Metric.SPO2))
        assertEquals(90_000L, measureTimeoutMs(Metric.HRV))
        assertEquals(90_000L, measureTimeoutMs(Metric.STRESS))
        assertEquals(60_000L, measureTimeoutMs(Metric.TEMP))
    }

    @Test fun measureVariantStockBytes() {
        val v = Cmd.measureVariant(Metric.HEART_RATE, Cmd.MeasureVariant.V_STOCK)
        assertEquals(1, v.size)
        assertArrayEquals(hexBytes("FD DA 10 07 01 09 01"), v[0])
    }

    @Test fun actigraphyDecode() {
        // day=0 page=0, samples: still(10), moved(128+5=133), still(0)
        val payload = byteArrayOf(0, 0, 10, 133.toByte(), 0)
        val s = ProbeCatalog.decodeActigraphy(payload)
        assertTrue(s.contains("moved=1"))
        assertTrue(s.contains("max=5"))
        assertTrue(s.contains("n=3"))
    }

    @Test fun sleepHypnoDecode() {
        // header day=0, then awake@22:30, light@23:00, deep@01:00
        val payload = byteArrayOf(0, 0, 22, 30, 1, 23, 0, 2, 1, 0)
        val s = ProbeCatalog.decodeSleepHypno(payload)
        assertTrue(s.contains("awake@22:30"))
        assertTrue(s.contains("light@23:00"))
        assertTrue(s.contains("deep@01:00"))
    }

    @Test fun blockedPairsIncludeFactoryReset() {
        assertTrue(ProbeCatalog.isBlocked(3, 0)) // reset
        assertTrue(ProbeCatalog.isBlocked(3, 1)) // shutDown
        assertTrue(ProbeCatalog.isBlocked(1, 20)) // removeBond
        assertTrue(ProbeCatalog.isBlocked(4, 4)) // bootloader
        assertFalse(ProbeCatalog.isBlocked(2, 19)) // actigraphy SAFE
        assertFalse(ProbeCatalog.isBlocked(2, 14)) // sleep SAFE
    }

    @Test fun probeCatalogHasNoDangerous() {
        assertTrue(ProbeCatalog.ALL.none { ProbeCatalog.isBlocked(it.group, it.cmd) })
        assertTrue(ProbeCatalog.SAFE_READ.isNotEmpty())
    }
}
