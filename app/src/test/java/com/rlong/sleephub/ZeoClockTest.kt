package com.rlong.sleephub

import android.database.sqlite.SQLiteDatabase
import org.robolectric.RuntimeEnvironment
import com.rlong.sleephub.ring.data.HistoryWindow
import com.rlong.sleephub.ring.data.SleepNight
import com.rlong.sleephub.ring.data.WindowMode
import com.rlong.sleephub.zeo.ZStage
import com.rlong.sleephub.zeo.ZeoDb
import com.rlong.sleephub.zeo.ZeoNights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Live v0.2.2 numbers: REPORT_SLEEP startOfNight 0x500ABA90 = 1342880400 s, offset 449747227268 ms
 * → start 1792627627268 ms = 2026-10-21 17:07:07 PDT. (The pulled DB's sonhb 1343031997 = 0x500D0BBD is what the
 * true big-endian 50 0A BA 90 looks like after an LF→CRLF-corrupted adb pull; file was 3 bytes over a page multiple.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ZeoClockTest {
    private val son = 1342880400L; private val off = 449747227268L; private val expStart = 1792627627268L
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private val base = List(92) { if (it < 63) ZStage.UNDEFINED else ZStage.LIGHT }

    @Test fun wallAppliesOffsetOnceFromHbSeconds() {
        assertEquals(expStart, ZeoNights.wall(son, off))
        val w = ZeoNights.wallTimes(son, null, null, 92, off)
        assertEquals(expStart, w.start); assertEquals(expStart + 92 * 30_000L, w.end); assertEquals(expStart, w.displayStart)
        // garbage raw values are rejected, not converted
        assertNull(ZeoNights.plausible(son, 656766852L)); assertNull(ZeoNights.plausible(son, 0L))
        assertEquals(son + 3600, ZeoNights.plausible(son, son + 3600))
        // also matches the TIME_REPORT example: hb 1342881153453 ms at phone 2026-10-21 17:19:40 PDT
        assertTrue(Math.abs((1342881153453L + off) - 1792628380000L) < 2_000L)
    }

    @Test fun remapIsIdempotentAndRepairsCorruptRows() {
        val db = ZeoDb(RuntimeEnvironment.getApplication(), null)
        db.putRaw(son, -1, 656766852L, base, List(9) { ZStage.UNDEFINED }, 0, null, false, nowMs = 1792780000000L)
        assertEquals(son * 1000, db.nights(0, Long.MAX_VALUE, false).single().start)   // no offset yet: raw hb time
        // simulate the corrupted v0.2.2 row
        db.writableDatabase.execSQL("UPDATE nights SET start=114356211743216, \"end\"=-65963695204110, display_start=1106514079445")
        repeat(3) { db.remap(off) }
        val n = db.nights(0, Long.MAX_VALUE, false).single()
        assertEquals(expStart, n.start); assertEquals(expStart + 92 * 30_000L, n.end); assertEquals(expStart, n.displayStart)
    }

    @Test fun v1RowsAreRepairedOnUpgrade() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("zeo-up.db")
        val v1 = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("zeo-up.db").apply { parentFile?.mkdirs() }, null)
        v1.execSQL("CREATE TABLE nights (sonhb INTEGER NOT NULL, demo INTEGER NOT NULL DEFAULT 0, start INTEGER NOT NULL, end INTEGER, base BLOB, display BLOB, display_start INTEGER, zq INTEGER, updated INTEGER, PRIMARY KEY(sonhb, demo))")
        v1.execSQL("INSERT INTO nights VALUES (1342880400, 0, 114356211743216, -65963695204110, ?, ?, 1106514079445, 0, 4405048973558)", arrayOf<Any>(ZeoNights.encode(base), ByteArray(9)))
        v1.version = 1; v1.close()
        val db = ZeoDb(ctx, "zeo-up.db"); db.remap(off)
        val n = db.nights(0, Long.MAX_VALUE, false).single()
        assertEquals(expStart, n.start); assertEquals(92, n.base.size)
    }

    @Test fun historyNoonWindowFindsTheNight() {
        val db = ZeoDb(RuntimeEnvironment.getApplication(), null)
        db.putRaw(son, -1, -1, base, emptyList(), 0, off, false)
        val now = 1792630780000L   // 2026-10-21 17:19:40 PDT... any time that day
        val (t0, t1) = HistoryWindow.of(HistoryWindow.keyContaining(expStart, WindowMode.NOON, la), WindowMode.NOON, la)
        assertTrue(expStart in t0 until t1)
        assertEquals(1, db.nights(t0, t1, false).size)
        val (p0, p1) = HistoryWindow.partialWindow(expStart + 3_600_000L, la)   // Today (partial)
        assertEquals(1, db.nights(p0, p1, false).size)
        // the previous noon-to-noon window does not contain it
        val (q0, q1) = HistoryWindow.of(SleepNight.shiftKey(HistoryWindow.keyContaining(expStart, WindowMode.NOON, la), -1, la), WindowMode.NOON, la)
        assertEquals(0, db.nights(q0, q1, false).size)
        assertTrue(now > 0)
    }
}
