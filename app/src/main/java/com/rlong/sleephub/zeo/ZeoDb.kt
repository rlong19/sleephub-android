package com.rlong.sleephub.zeo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rlong.sleephub.ring.proto.SleepSeg

/** One persisted Zeo night (from REPORT_SLEEP / SLEEP_SEND replies). Times are wall-clock ms (hb clock + offset). */
data class ZeoNight(val startOfNightHb: Long, val start: Long, val end: Long?, val base: List<ZStage>, val display: List<ZStage>,
                    val displayStart: Long, val zq: Int, val demo: Boolean) {
    /** 30-s base hypnogram as ring-style segments (0 awake, 1 light, 2 deep, 3 REM); undefined epochs are gaps. */
    val segments: List<SleepSeg> get() = ZeoNights.segments(start, base, 30_000L)
}

object ZeoNights {
    /** Stage 4 = unscored (base value 0 = undefined / not yet scored, per HypnogramCodec). */
    const val UNSCORED = 4
    fun ringStage(s: ZStage): Int = when (s) { ZStage.WAKE -> 0; ZStage.LIGHT -> 1; ZStage.DEEP -> 2; ZStage.REM -> 3; else -> UNSCORED }

    /** Merge consecutive equal epochs into segments. */
    fun segments(t0: Long, epochs: List<ZStage>, epochMs: Long): List<SleepSeg> {
        val out = ArrayList<SleepSeg>(); var cur: Int? = null; var from = 0
        for (i in 0..epochs.size) {
            val st: Int? = if (i < epochs.size) ringStage(epochs[i]) else null
            if (i == epochs.size || st != cur) {
                if (cur != null) out += SleepSeg(cur, t0 + from * epochMs, t0 + i * epochMs)
                cur = st; from = i
            }
        }
        return out
    }
    fun encode(l: List<ZStage>) = ByteArray(l.size) { l[it].code.toByte() }
    fun decode(b: ByteArray?) = b?.map { ZStage.of(it.toInt()) } ?: emptyList()
    /** Phone epoch ms of a raw headband-clock second count; the offset (phone ms − headband ms) is applied exactly once here. */
    fun wall(hbSec: Long, offsetMs: Long?) = hbSec * 1000L + (offsetMs ?: 0L)
    data class Wall(val start: Long, val end: Long, val displayStart: Long)
    /** Raw hb seconds only. end defaults to start + base epochs × 30 s, display start to start. */
    fun wallTimes(sonHb: Long, eonHb: Long?, dstHb: Long?, baseCount: Int, offsetMs: Long?): Wall {
        val start = wall(sonHb, offsetMs)
        return Wall(start, eonHb?.let { wall(it, offsetMs) } ?: (start + baseCount * 30_000L), dstHb?.let { wall(it, offsetMs) } ?: start)
    }
    /** A raw hb-second value belonging to the night that starts at [sonHb] (within −1 … +2 days), else null. */
    fun plausible(sonHb: Long, v: Long): Long? = v.takeIf { it > 0 && it in (sonHb - 86_400)..(sonHb + 2 * 86_400) }
}

/**
 * Local store so Zeo hypnograms accumulate across nights.
 * Convention (v0.2.3): the RAW headband seconds are the source of truth (sonhb, eon_hb, dst_hb). The wall-clock columns
 * start/end/display_start are epoch ms of PHONE time, always recomputed as hbSec*1000 + offsetMs from the raw columns
 * (never from a stored wall value), so re-mapping is idempotent. 'updated' = phone System.currentTimeMillis().
 */
class ZeoDb(ctx: Context?, name: String? = "zeo.db") : SQLiteOpenHelper(ctx, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE nights (sonhb INTEGER NOT NULL, demo INTEGER NOT NULL DEFAULT 0, eon_hb INTEGER, dst_hb INTEGER, offset_ms INTEGER, start INTEGER NOT NULL, end INTEGER, base BLOB, display BLOB, display_start INTEGER, zq INTEGER, updated INTEGER, PRIMARY KEY(sonhb, demo))")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {   // v1 stored derived (and possibly corrupted) wall times only: keep sonhb + hypnograms, drop the rest
            db.execSQL("ALTER TABLE nights RENAME TO nights_v1")
            onCreate(db)
            db.execSQL("INSERT INTO nights (sonhb, demo, start, base, display, zq, updated) SELECT sonhb, demo, sonhb*1000, base, display, zq, ? FROM nights_v1", arrayOf<Any>(System.currentTimeMillis()))
            db.execSQL("DROP TABLE nights_v1")
        }
    }

    /** Upsert from a sleep report; never replaces a night with a shorter base hypnogram (a partial re-send). */
    fun put(m: ZeoMsg.Sleep, offsetMs: Long?, demo: Boolean, nowMs: Long = System.currentTimeMillis()) =
        putRaw(m.startOfNight, m.endOfNight, m.displayStartTime, m.base, m.display, m.zq, offsetMs, demo, nowMs)

    fun putRaw(sonHb: Long, eonHb: Long, dstHb: Long, base: List<ZStage>, display: List<ZStage>, zq: Int, offsetMs: Long?, demo: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (sonHb <= 0 || base.isEmpty()) return
        val d = if (demo) 1 else 0
        val old = readableDatabase.rawQuery("SELECT length(base) FROM nights WHERE sonhb=? AND demo=?", arrayOf(sonHb.toString(), d.toString())).use { if (it.moveToFirst()) it.getInt(0) else -1 }
        if (old > base.size) return
        val w = ZeoNights.wallTimes(sonHb, ZeoNights.plausible(sonHb, eonHb), ZeoNights.plausible(sonHb, dstHb), base.size, offsetMs)
        writableDatabase.insertWithOnConflict("nights", null, ContentValues().apply {
            put("sonhb", sonHb); put("demo", d)
            ZeoNights.plausible(sonHb, eonHb)?.let { put("eon_hb", it) } ?: putNull("eon_hb")
            ZeoNights.plausible(sonHb, dstHb)?.let { put("dst_hb", it) } ?: putNull("dst_hb")
            if (offsetMs != null) put("offset_ms", offsetMs) else putNull("offset_ms")
            put("start", w.start); put("end", w.end); put("display_start", w.displayStart)
            put("base", ZeoNights.encode(base)); put("display", ZeoNights.encode(display)); put("zq", zq); put("updated", nowMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Idempotent repair/re-map of every real (non-demo) row: wall times recomputed from the raw hb seconds + [offsetMs]. */
    fun remap(offsetMs: Long) {
        val rows = readableDatabase.rawQuery("SELECT sonhb, eon_hb, dst_hb, length(base) FROM nights WHERE demo=0", null).use { c ->
            val l = ArrayList<LongArray>(); while (c.moveToNext()) l += longArrayOf(c.getLong(0), if (c.isNull(1)) -1 else c.getLong(1), if (c.isNull(2)) -1 else c.getLong(2), c.getLong(3)); l }
        for (r in rows) {
            val w = ZeoNights.wallTimes(r[0], r[1].takeIf { it > 0 }, r[2].takeIf { it > 0 }, r[3].toInt(), offsetMs)
            writableDatabase.execSQL("UPDATE nights SET start=?, \"end\"=?, display_start=?, offset_ms=? WHERE sonhb=? AND demo=0", arrayOf<Any>(w.start, w.end, w.displayStart, offsetMs, r[0]))
        }
    }
    @Deprecated("use remap") fun remapOld(offsetMs: Long) = remap(offsetMs)

    /**
     * Nights overlapping [t0, t1) (phone epoch ms, e.g. a noon-to-noon History window).
     * rawQuery binds args as TEXT; an expression (no column affinity) compared with TEXT is always "less", so the old
     * `start + length(base)*30000 > ?` was never true and History never showed a Zeo night. Hence the CASTs.
     */
    fun nights(t0: Long, t1: Long, demo: Boolean): List<ZeoNight> = readableDatabase.rawQuery(
        "SELECT sonhb,start,\"end\",base,display,display_start,zq FROM nights WHERE demo=? AND start < CAST(? AS INTEGER) AND start + length(base) * 30000 > CAST(? AS INTEGER) ORDER BY start",
        arrayOf(if (demo) "1" else "0", t1.toString(), t0.toString())).use { c ->
        val out = ArrayList<ZeoNight>()
        while (c.moveToNext()) out += ZeoNight(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), ZeoNights.decode(c.getBlob(3)), ZeoNights.decode(c.getBlob(4)), c.getLong(5), c.getInt(6), demo)
        out
    }
    fun count(demo: Boolean): Int = readableDatabase.rawQuery("SELECT count(*) FROM nights WHERE demo=?", arrayOf(if (demo) "1" else "0")).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun newest(demo: Boolean): Long? = readableDatabase.rawQuery("SELECT max(start + length(base) * 30000) FROM nights WHERE demo=?", arrayOf(if (demo) "1" else "0")).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
}
