package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.data.NightCsv
import com.rlong.sleephub.ring.data.SleepNight
import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class SleepHistoryTest {
    private val tz = TimeZone.getTimeZone("America/Los_Angeles")
    private val now = 1_760_900_000_000L          // 2025-10-19 ~12:53 PDT
    private val p = Parser(tz) { now }
    private fun le32b(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
    private fun f32b(f: Float) = le32b(java.lang.Float.floatToIntBits(f).toLong() and 0xFFFFFFFFL)

    @Test fun actionDetailsCommandAndParse() {
        assertArrayEquals(hexBytes("FD DA 10 08 02 13 03 05"), Cmd.actionDetails(3, 5))
        val e = p.frame(Frame.build(2, 19, byteArrayOf(2, 7, 10, 133.toByte(), 0))) as RingEvent.ActionPage
        assertEquals(2, e.day); assertEquals(7, e.page); assertEquals(listOf(10, 133, 0), e.values)
        assertEquals(0, Actigraphy.magnitude(128)); assertEquals(5, Actigraphy.magnitude(133)); assertEquals(0, Actigraphy.magnitude(10))
    }

    @Test fun actionCollectorFollowsEightPages() {
        val c = ActionCollector()
        for (pg in 0 until 7) {
            val (next, done) = c.onPage(RingEvent.ActionPage(1, pg, List(180) { pg }))
            assertArrayEquals(Cmd.actionDetails(1, pg + 1), next); assertNull(done)
        }
        val (next, done) = c.onPage(RingEvent.ActionPage(1, 7, List(180) { 7 }))
        assertNull(next); assertEquals(1440, done!!.size); assertEquals(0, done[0]); assertEquals(7, done[1439])
    }

    @Test fun actionCollectorMissingPageGivesEmpty() {
        val c = ActionCollector()
        c.onPage(RingEvent.ActionPage(0, 0, listOf(1)))
        assertEquals(emptyList<Int>(), c.onPage(RingEvent.ActionPage(0, 7, listOf(1))).second)
    }

    @Test fun sleepListAndDetails() {
        val t1 = RingTime.toDevice(now - 10 * 3600_000L, tz); val t2 = RingTime.toDevice(now - 2 * 3600_000L, tz)
        val list = p.frame(Frame.build(7, 4, le32b(t1) + le32b(t2))) as RingEvent.SleepList
        assertEquals(listOf(now - 10 * 3600_000L, now - 2 * 3600_000L).map { it / 1000 * 1000 }, list.starts)
        val det = byteArrayOf(1, 2) + le32b(t1) + le32b(t2) + f32b(35f) + f32b(90.5f) + f32b(77f)
        val s = (p.frame(Frame.build(7, 5, det)) as RingEvent.SleepDetails).session
        assertEquals(1, s.id); assertTrue(s.isNap); assertEquals(35f, s.sleepMinutes, 0f); assertEquals(90.5f, s.efficiency, 0f); assertEquals(77f, s.score, 0f)
        assertEquals(RingTime.fromDevice(t1, tz), s.start); assertEquals(RingTime.fromDevice(t2, tz), s.end)
        assertNull(p.frame(Frame.build(7, 5, ByteArray(10))))
        assertNull(p.frame(Frame.build(7, 4, ByteArray(5))))
    }

    @Test fun sessionStagesAnchorAcrossMidnight() {
        // session start 22:55 on day-1; points awake 23:00, light 23:30, deep 01:00, awake 06:45
        val anchor = p.dayStart(1) + (22 * 60 + 55) * 60_000L
        val raw = byteArrayOf(0, 0, 23, 0, 1, 23, 30, 2, 1, 0, 0, 6, 45)
        val e = p.frame(Frame.build(7, 6, raw)) as RingEvent.SessionStages
        assertEquals(0, e.id)
        val segs = p.stagesAt(e.raw, anchor)
        assertEquals(3, segs.size)
        assertEquals(anchor + 5 * 60_000L, segs[0].start)
        assertEquals(listOf(30L, 90L, 345L), segs.map { it.minutes })
        assertEquals(p.dayStart(0) + (6 * 60 + 45) * 60_000L, segs.last().end)
        assertTrue(p.stagesAt(byteArrayOf(0, 1), anchor).isEmpty())
    }

    @Test fun nightModelTotalsNapsAndCsv() {
        val key = p.dayStart(0)
        val segs = listOf(SleepSeg(1, key - 3600_000L, key), SleepSeg(2, key, key + 2 * 3600_000L), SleepSeg(3, key + 2 * 3600_000L, key + 3 * 3600_000L), SleepSeg(0, key + 3 * 3600_000L, key + 3 * 3600_000L + 600_000L))
        val nap = SleepSession(1, 2, key + 14 * 3600_000L, key + 14 * 3600_000L + 1800_000L, 28f, 90f, 70f)
        val n = SleepNight(key, segs, listOf(key to 140, key + 60_000L to 20), listOf(nap))
        assertArrayEquals(intArrayOf(10, 60, 120, 60), n.totals)
        assertEquals(240, n.asleepMinutes); assertEquals(key - 3600_000L, n.start); assertEquals(1, n.naps.size)
        assertFalse(n.isEmpty); assertTrue(SleepNight(key, emptyList(), emptyList(), emptyList()).isEmpty)
        val csv = NightCsv.toString(listOf(n), tz).trim().split("\r\n")
        assertEquals(NightCsv.HEADER, csv[0])
        assertEquals(1 + 1 + 4 + 1 + 2, csv.size)
        assertTrue(csv[1].contains(",summary,") && csv[1].contains("deep=120"))
        assertTrue(csv.any { it.contains(",nap,") && it.contains("type=2") })
        assertTrue(csv.any { it.contains(",movement,") && it.endsWith(",140,12,") })
        assertTrue(csv.all { it.split(",").size == 9 })
    }

    @Test fun sessionHypnogramFallback() {
        val key = p.dayStart(0)
        val main = SleepSession(0, 1, key - 3600_000L, key + 5 * 3600_000L, 300f, 80f, 75f)
        val n = SleepNight(key, emptyList(), emptyList(), listOf(main), mapOf(main.start to listOf(SleepSeg(2, main.start, main.end))))
        assertEquals(360, n.totals[2])
    }

    @Test fun keysShiftByCalendarDay() {
        val k = SleepNight.keyFor(now, tz)
        assertEquals(p.dayStart(0), k)
        assertEquals(p.dayStart(3), SleepNight.shiftKey(k, -3, tz))
        // DST fall-back (2025-11-02 in LA) still lands on midnight
        val nov3 = SleepNight.keyFor(1_762_196_400_000L, tz)
        val nov1 = SleepNight.shiftKey(nov3, -2, tz)
        assertEquals(0, java.util.Calendar.getInstance(tz).apply { timeInMillis = nov1 }.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test fun autoSyncAtMostHourly() {
        assertTrue(Ring.autoSyncDue(0, now))
        assertFalse(Ring.autoSyncDue(now - 44 * 60_000L, now))
        assertTrue(Ring.autoSyncDue(now - 45 * 60_000L, now))
        assertTrue(Ring.autoSyncDue(now + 1000, now))   // clock moved back
    }

    @Test fun historyRangeMatchesSdkEnum() { assertEquals(14, Cmd.HISTORY_MAX_DAY) }
}
