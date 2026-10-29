package com.rlong.sleephub.ring

import org.robolectric.RuntimeEnvironment
import com.rlong.sleephub.ring.data.RingDb
import com.rlong.sleephub.ring.data.SleepNight
import com.rlong.sleephub.ring.data.WindowMode
import com.rlong.sleephub.ring.proto.SleepSeg
import com.rlong.sleephub.ring.proto.SleepSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RingDbTest {
    private lateinit var db: RingDb
    private val key = SleepNight.keyFor(1_760_900_000_000L)
    private val segs = listOf(SleepSeg(1, key - 3600_000L, key), SleepSeg(2, key, key + 3600_000L))

    @Before fun setUp() { val ctx = RuntimeEnvironment.getApplication(); ctx.deleteDatabase(RingDb.NAME); db = RingDb(ctx) }
    @After fun tearDown() { db.close() }

    @Test fun nightsDedupByDateAndSurviveEmptyReplies() {
        assertTrue(db.putSleep(key, segs, byteArrayOf(0), false))
        assertTrue(db.putSleep(key, segs, byteArrayOf(0), false))          // re-sync same night
        assertEquals(listOf(key), db.nights(false))
        assertEquals(2, db.sleep(key, false).size)
        assertFalse(db.putSleep(key, emptyList(), byteArrayOf(0), false))  // ring overwrote that day → keep stored night
        assertEquals(2, db.sleep(key, false).size)
        db.putSleep(key, segs.take(1), byteArrayOf(0), false)              // newer data replaces, not appends
        assertEquals(1, db.sleep(key, false).size)
        assertTrue(db.nights(true).isEmpty())
    }

    @Test fun movementStoredByMinuteAndWindowed() {
        val day = SleepNight.shiftKey(key, -1)
        val raw = List(1440) { if (it % 2 == 0) 140 else 0 }
        assertEquals(1440, db.putMovementDay(day, raw, Long.MAX_VALUE, false))
        assertEquals(1440, db.putMovementDay(day, raw, Long.MAX_VALUE, false))   // upsert, no dupes
        assertEquals(1440, db.movement(day, key - 1, false).size)
        assertEquals(0, db.putMovementDay(key, List(1440) { 0 }, Long.MAX_VALUE, false))  // empty day ignored
        assertEquals(10, db.putMovementDay(key, raw, key + 9 * 60_000L, false))           // today truncated
        db.putSleep(key, segs, byteArrayOf(0), false)
        val n = db.night(key, false)
        assertEquals((key - 5400_000L), db.window(n).first)
        assertEquals(n.movement.size, db.movement(key - 5400_000L, key + 5400_000L, false).size)
    }

    @Test fun sessionsAndNapsAttachToWakeDate() {
        val main = SleepSession(0, 1, key - 2 * 3600_000L, key + 6 * 3600_000L, 420f, 88f, 80f)
        val nap = SleepSession(1, 2, key + 14 * 3600_000L, key + 14 * 3600_000L + 1800_000L, 25f, 90f, 60f)
        db.putSession(main, false); db.putSession(nap, false); db.putSession(nap, false)
        assertTrue(db.putSessionStages(main.start, listOf(SleepSeg(3, main.start, main.end)), false))
        assertFalse(db.putSessionStages(main.start, emptyList(), false))
        assertEquals(listOf(key), db.nights(false))
        val n = db.night(key, false)
        assertEquals(2, n.sessions.size); assertEquals(1, n.naps.size)
        assertEquals(480, n.totals[3])   // hypnogram falls back to session stages
        assertEquals(1, db.allNights(false).size)
    }

    @Test fun clearDemoKeepsRealNights() {
        db.putSleep(key, segs, byteArrayOf(0), true); db.putSleep(SleepNight.shiftKey(key, -1), segs, byteArrayOf(0), false)
        db.putMovementDay(key, List(10) { 140 }, Long.MAX_VALUE, true)
        db.clearDemo()
        assertTrue(db.nights(true).isEmpty()); assertEquals(1, db.nights(false).size)
        assertTrue(db.movement(0, Long.MAX_VALUE, true).isEmpty())
    }

    @Test fun vitalsDedupByTimeDaysAndSleepBands() {
        val m = com.rlong.sleephub.ring.proto.Metric.HEART_RATE
        val s = listOf(com.rlong.sleephub.ring.proto.Sample(m, key + 3600_000L, 70.0, "timing"), com.rlong.sleephub.ring.proto.Sample(m, key + 3900_000L, 0xFF.toDouble(), "timing"))
        db.addSamples(s, false); db.addSamples(s, false)                    // re-sync: same date+time → no dupes
        db.addSamples(emptyList(), false)                                   // empty reply deletes nothing
        db.addSamples(listOf(com.rlong.sleephub.ring.proto.Sample(m, key + 3600_000L, 70.0, "measured")), false)
        db.addSamples(listOf(com.rlong.sleephub.ring.proto.Sample(com.rlong.sleephub.ring.proto.Metric.SPO2, SleepNight.shiftKey(key, -2) + 60_000L, 97.0, "timing")), false)
        assertEquals(listOf(key, SleepNight.shiftKey(key, -2)), db.vitalsDays(false))
        db.putSleep(key, segs, byteArrayOf(0), false)                         // 23:00 → 01:00 overlaps this day 00:00-01:00
        val d = db.vitalsDay(key, false)
        assertEquals(2, d.of(m).size)                                         // invalid 255 hidden, periodic + one-shot kept
        assertEquals(1, d.oneShot(m).size)
        assertEquals(listOf(key to key + 3600_000L), d.sleepBands)
        assertEquals(3, db.allVitals(false).size)
        assertTrue(db.vitalsDays(true).isEmpty())
    }

    @Test fun historyPageNoonWindowHoldsTheNight() {
        db.putSleep(key, segs, byteArrayOf(0), false)                       // 23:00 → 01:00
        db.putMovementDay(SleepNight.shiftKey(key, -1), List(1440) { 140 }, Long.MAX_VALUE, false)
        val pg = db.historyPage(key, WindowMode.NOON, false)
        assertEquals(2, pg.segments.size); assertEquals(720, pg.movement.size)   // only 12:00–24:00 of the previous day
        assertEquals(listOf(key - 3600_000L to key + 3600_000L), pg.vitals.sleepBands)
        val mid = db.historyPage(key, WindowMode.MIDNIGHT, false)
        assertEquals(0, mid.movement.size); assertEquals(1, mid.segments.size)    // only the 00:00–01:00 segment overlaps
        assertEquals(listOf(key), db.historyDays(false))
    }
}
