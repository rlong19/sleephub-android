package com.rlong.sleephub.ring.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rlong.sleephub.ring.proto.Metric
import com.rlong.sleephub.ring.proto.Sample
import com.rlong.sleephub.ring.proto.Valid
import com.rlong.sleephub.ring.proto.SleepSeg
import com.rlong.sleephub.ring.proto.SleepSession
import java.io.File

/** Local-only storage (replaces the original's GreenDAO DB + cloud sync). */
class RingDb(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 3) {
    companion object { const val NAME = "ring.db" }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (metric TEXT NOT NULL, ts INTEGER NOT NULL, value REAL NOT NULL, source TEXT, demo INTEGER DEFAULT 0, PRIMARY KEY(metric, ts, source))")
        db.execSQL("CREATE TABLE sleep (night INTEGER NOT NULL, stage INTEGER, start INTEGER NOT NULL, end INTEGER, demo INTEGER DEFAULT 0, PRIMARY KEY(night, start))")
        db.execSQL("CREATE TABLE steps_day (day INTEGER PRIMARY KEY, steps INTEGER, distance INTEGER, calories INTEGER, demo INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE steps_30min (ts INTEGER PRIMARY KEY, steps INTEGER, demo INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE sleep_raw (night INTEGER PRIMARY KEY, raw BLOB, demo INTEGER DEFAULT 0)")
        createV2(db); createV3(db)
    }
    /** v3: sleep periods detected by the app (any time of day) for SpO2 dip counting. */
    private fun createV3(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS app_sleep (start INTEGER PRIMARY KEY, end INTEGER, reason TEXT)") }
    fun openSleep(start: Long, reason: String) { writableDatabase.insertWithOnConflict("app_sleep", null, ContentValues().apply { put("start", start); putNull("end"); put("reason", reason) }, SQLiteDatabase.CONFLICT_IGNORE) }
    fun closeSleep(end: Long) { writableDatabase.execSQL("UPDATE app_sleep SET end=? WHERE end IS NULL", arrayOf(end)) }
    /** Periods overlapping [from, to]; an open period ends at [to]. */
    fun appSleep(from: Long, to: Long): List<Pair<Long, Long>> = readableDatabase.rawQuery("SELECT start, end FROM app_sleep WHERE start < ? AND (end IS NULL OR end > ?) ORDER BY start", arrayOf(to.toString(), from.toString())).use { c ->
        val l = ArrayList<Pair<Long, Long>>(); while (c.moveToNext()) l += c.getLong(0) to (if (c.isNull(1)) to else c.getLong(1)); l }
    /** ~10th percentile HR over the last 7 days (resting baseline) or null. */
    fun restingHr(now: Long): Double? = readableDatabase.rawQuery("SELECT value FROM samples WHERE metric='HEART_RATE' AND demo=0 AND ts>? AND value BETWEEN 30 AND 200 ORDER BY value", arrayOf((now - 7 * 86400_000L).toString())).use { c ->
        if (c.count < 20) null else { c.moveToPosition(c.count / 10); c.getDouble(0) } }
    /** v2: per-minute movement (2/0x13) and sleep sessions (7/04-7/06). */
    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS movement (ts INTEGER NOT NULL, raw INTEGER NOT NULL, demo INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(ts, demo))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_session (start INTEGER NOT NULL, demo INTEGER NOT NULL DEFAULT 0, id INTEGER, type INTEGER, end INTEGER, sleep_min REAL, efficiency REAL, score REAL, night INTEGER NOT NULL, PRIMARY KEY(start, demo))")
        db.execSQL("CREATE TABLE IF NOT EXISTS session_seg (session_start INTEGER NOT NULL, stage INTEGER, start INTEGER NOT NULL, end INTEGER, demo INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(session_start, start, demo))")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) createV2(db); if (o < 3) createV3(db) }
    private fun d(demo: Boolean) = if (demo) "1" else "0"

    fun addSamples(l: List<Sample>, demo: Boolean) { val db = writableDatabase; db.beginTransaction()
        try { l.forEach { s -> db.insertWithOnConflict("samples", null, ContentValues().apply { put("metric", s.metric.name); put("ts", s.time); put("value", s.value); put("source", s.source); put("demo", if (demo) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful() } finally { db.endTransaction() } }
    /** Hides bogus rows stored by older builds (e.g. 6553.5 °C from a 0xFFFF off-finger temperature). */
    private fun validSql(m: Metric): String = Valid.range(m)?.let { " AND value BETWEEN ${it.start} AND ${it.endInclusive}" } ?: ""
    fun samples(m: Metric, from: Long, to: Long = Long.MAX_VALUE, demo: Boolean): List<Sample> =
        readableDatabase.rawQuery("SELECT ts, value, source FROM samples WHERE metric=? AND ts BETWEEN ? AND ? AND demo=?${validSql(m)} ORDER BY ts", arrayOf(m.name, from.toString(), to.toString(), if (demo) "1" else "0")).use { c ->
            val l = ArrayList<Sample>(); while (c.moveToNext()) l += Sample(m, c.getLong(0), c.getDouble(1), c.getString(2)); l }
    /** Local-midnight keys of days that have any valid vitals sample, newest first. */
    fun vitalsDays(demo: Boolean): List<Long> {
        val hours = HashSet<Long>()
        for (m in Vitals.METRICS) readableDatabase.rawQuery("SELECT DISTINCT ts/3600000 FROM samples WHERE metric=? AND demo=?${validSql(m)}", arrayOf(m.name, d(demo))).use { c -> while (c.moveToNext()) hours += c.getLong(0) }
        return hours.map { SleepNight.keyFor(it * 3600_000L) }.distinct().sortedDescending()
    }
    /** All valid vitals for one local day [key, next midnight). */
    fun vitalsDay(key: Long, demo: Boolean): VitalsDay {
        val end = SleepNight.shiftKey(key, 1) - 1
        val bands = listOf(key, SleepNight.shiftKey(key, 1)).flatMap { k -> val n = night(k, demo)
            listOfNotNull(if (n.start != null && n.end != null) n.start!! to n.end!! else null) + n.naps.map { it.start to it.end } }
            .mapNotNull { (a, b) -> if (b < key || a > end) null else maxOf(a, key) to minOf(b, end) }
        return VitalsDay(key, Vitals.METRICS.associateWith { samples(it, key, end, demo) }, bands)
    }
    /** One History page: sleep from the nights overlapping the window, movement and vitals inside it. */
    fun historyPage(key: Long, mode: WindowMode, demo: Boolean, window: Pair<Long, Long>? = null): HistoryPage {
        val (t0, t1) = window ?: HistoryWindow.of(key, mode)
        val nights = listOf(key, SleepNight.shiftKey(key, 1)).map { night(it, demo) }
        val segs = nights.flatMap { it.hypnogram }.filter { it.end > t0 && it.start < t1 }
        val bands = nights.flatMap { n -> listOfNotNull(if (n.start != null && n.end != null) n.start!! to n.end!! else null) + n.naps.map { it.start to it.end } }
            .filter { it.second > t0 && it.first < t1 }.map { maxOf(it.first, t0) to minOf(it.second, t1) }.distinct()
        val vit = VitalsDay(key, Vitals.METRICS.associateWith { samples(it, t0, t1 - 1, demo) }, bands)
        return HistoryPage(key, t0, t1, segs, movement(t0, t1 - 1, demo), vit, nights[0])
    }
    fun historyDays(demo: Boolean): List<Long> = (nights(demo) + vitalsDays(demo)).distinct().sortedDescending()
    fun allVitals(demo: Boolean): List<Sample> = Vitals.METRICS.flatMap { samples(it, 0, Long.MAX_VALUE, demo) }.sortedBy { it.time }
    fun latest(m: Metric, demo: Boolean): Sample? = readableDatabase.rawQuery("SELECT ts, value, source FROM samples WHERE metric=? AND demo=?${validSql(m)} ORDER BY ts DESC LIMIT 1", arrayOf(m.name, if (demo) "1" else "0")).use { c ->
        if (c.moveToFirst()) Sample(m, c.getLong(0), c.getDouble(1), c.getString(2)) else null }

    /** Replaces the night keyed by [night] (de-duplicated by date). Empty replies are ignored so a night survives after the ring overwrites it. Returns true if stored. */
    fun putSleep(night: Long, segs: List<SleepSeg>, raw: ByteArray, demo: Boolean): Boolean { if (segs.isEmpty()) return false; val db = writableDatabase; db.beginTransaction()
        try { db.delete("sleep", "night=? AND demo=?", arrayOf(night.toString(), if (demo) "1" else "0"))
            segs.forEach { s -> db.insertWithOnConflict("sleep", null, ContentValues().apply { put("night", night); put("stage", s.stage); put("start", s.start); put("end", s.end); put("demo", if (demo) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.insertWithOnConflict("sleep_raw", null, ContentValues().apply { put("night", night); put("raw", raw); put("demo", if (demo) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful() } finally { db.endTransaction() }; return true }
    /** Nights with stage or session data, newest first. */
    fun nights(demo: Boolean): List<Long> = readableDatabase.rawQuery("SELECT night FROM sleep WHERE demo=? UNION SELECT night FROM sleep_session WHERE demo=? ORDER BY 1 DESC", arrayOf(d(demo), d(demo))).use { c -> val l = ArrayList<Long>(); while (c.moveToNext()) l += c.getLong(0); l }

    /** One day of 2/0x13 raw bytes starting at [dayStart]; minutes after [limit] are dropped. All-zero/empty days are ignored. */
    fun putMovementDay(dayStart: Long, raw: List<Int>, limit: Long, demo: Boolean): Int {
        if (raw.none { it > 0 }) return 0
        val db = writableDatabase; db.beginTransaction(); var n = 0
        try { raw.forEachIndexed { i, v -> val ts = dayStart + i * 60_000L
                if (ts <= limit && v >= 0) { db.insertWithOnConflict("movement", null, ContentValues().apply { put("ts", ts); put("raw", v); put("demo", if (demo) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE); n++ } }
            db.setTransactionSuccessful() } finally { db.endTransaction() }
        return n
    }
    fun movement(from: Long, to: Long, demo: Boolean): List<Pair<Long, Int>> = readableDatabase.rawQuery("SELECT ts, raw FROM movement WHERE ts BETWEEN ? AND ? AND demo=? ORDER BY ts", arrayOf(from.toString(), to.toString(), d(demo))).use { c ->
        val l = ArrayList<Pair<Long, Int>>(); while (c.moveToNext()) l += c.getLong(0) to c.getInt(1); l }

    /** Sessions are keyed by start time (de-duplicated); night = local midnight of the wake-up date. */
    fun putSession(s: SleepSession, demo: Boolean) {
        writableDatabase.insertWithOnConflict("sleep_session", null, ContentValues().apply { put("start", s.start); put("demo", if (demo) 1 else 0); put("id", s.id); put("type", s.type)
            put("end", s.end); put("sleep_min", s.sleepMinutes); put("efficiency", s.efficiency); put("score", s.score); put("night", SleepNight.keyFor(s.end)) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun putSessionStages(sessionStart: Long, segs: List<SleepSeg>, demo: Boolean): Boolean { if (segs.isEmpty()) return false; val db = writableDatabase; db.beginTransaction()
        try { db.delete("session_seg", "session_start=? AND demo=?", arrayOf(sessionStart.toString(), d(demo)))
            segs.forEach { s -> db.insertWithOnConflict("session_seg", null, ContentValues().apply { put("session_start", sessionStart); put("stage", s.stage); put("start", s.start); put("end", s.end); put("demo", if (demo) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful() } finally { db.endTransaction() }; return true }
    fun sessions(night: Long, demo: Boolean): List<SleepSession> = readableDatabase.rawQuery("SELECT id, type, start, end, sleep_min, efficiency, score FROM sleep_session WHERE night=? AND demo=? ORDER BY start", arrayOf(night.toString(), d(demo))).use { c ->
        val l = ArrayList<SleepSession>(); while (c.moveToNext()) l += SleepSession(c.getInt(0), c.getInt(1), c.getLong(2), c.getLong(3), c.getFloat(4), c.getFloat(5), c.getFloat(6)); l }
    fun sessionStages(sessionStart: Long, demo: Boolean): List<SleepSeg> = readableDatabase.rawQuery("SELECT stage, start, end FROM session_seg WHERE session_start=? AND demo=? ORDER BY start", arrayOf(sessionStart.toString(), d(demo))).use { c ->
        val l = ArrayList<SleepSeg>(); while (c.moveToNext()) l += SleepSeg(c.getInt(0), c.getLong(1), c.getLong(2)); l }

    /** Everything stored for one night, with movement for the chart window (sleep span ± 30 min). */
    fun night(key: Long, demo: Boolean): SleepNight {
        val sessions = sessions(key, demo)
        val base = SleepNight(key, sleep(key, demo), emptyList(), sessions, sessions.associate { it.start to sessionStages(it.start, demo) })
        val (from, to) = window(base)
        return base.copy(movement = movement(from, to, demo))
    }
    fun window(n: SleepNight): Pair<Long, Long> {
        val all = n.hypnogram.flatMap { listOf(it.start, it.end) } + n.sessions.filter { !it.isNap }.flatMap { listOf(it.start, it.end) }
        return if (all.isEmpty()) SleepNight.defaultWindow(n.key) else (all.min() - 30 * 60_000L) to (all.max() + 30 * 60_000L)
    }
    fun allNights(demo: Boolean): List<SleepNight> = nights(demo).sorted().map { night(it, demo) }
    fun sleep(night: Long, demo: Boolean): List<SleepSeg> = readableDatabase.rawQuery("SELECT stage, start, end FROM sleep WHERE night=? AND demo=? ORDER BY start", arrayOf(night.toString(), if (demo) "1" else "0")).use { c ->
        val l = ArrayList<SleepSeg>(); while (c.moveToNext()) l += SleepSeg(c.getInt(0), c.getLong(1), c.getLong(2)); l }

    fun putStepsDay(day: Long, steps: Int, dist: Int, cal: Int, demo: Boolean) { writableDatabase.insertWithOnConflict("steps_day", null, ContentValues().apply { put("day", day); put("steps", steps); put("distance", dist); put("calories", cal); put("demo", if (demo) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun stepsDays(demo: Boolean): List<IntArray> = readableDatabase.rawQuery("SELECT day, steps, distance, calories FROM steps_day WHERE demo=? ORDER BY day DESC LIMIT 30", arrayOf(if (demo) "1" else "0")).use { c ->
        val l = ArrayList<IntArray>(); while (c.moveToNext()) l += intArrayOf((c.getLong(0) / 1000).toInt(), c.getInt(1), c.getInt(2), c.getInt(3)); l }
    fun putSteps30(start: Long, per: List<Int>, demo: Boolean) { val db = writableDatabase; db.beginTransaction()
        try { per.forEachIndexed { i, v -> db.insertWithOnConflict("steps_30min", null, ContentValues().apply { put("ts", start + i * 1800_000L); put("steps", v); put("demo", if (demo) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful() } finally { db.endTransaction() } }
    fun steps30(from: Long, to: Long, demo: Boolean): List<Pair<Long, Int>> = readableDatabase.rawQuery("SELECT ts, steps FROM steps_30min WHERE ts BETWEEN ? AND ? AND demo=? ORDER BY ts", arrayOf(from.toString(), to.toString(), if (demo) "1" else "0")).use { c ->
        val l = ArrayList<Pair<Long, Int>>(); while (c.moveToNext()) l += c.getLong(0) to c.getInt(1); l }

    fun clearDemo() { val db = writableDatabase; for (t in listOf("samples", "sleep", "steps_day", "steps_30min", "sleep_raw", "movement", "sleep_session", "session_seg")) db.delete(t, "demo=1", null) }

    /** Copy of the database (WAL-checkpointed) + per-table CSVs, for export/share. */
    fun export(ctx: Context, dir: File): List<File> {
        dir.mkdirs()
        val db = writableDatabase
        try { db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } } catch (_: Exception) {}
        val out = ArrayList<File>()
        val dbCopy = File(dir, "darings_export.db"); ctx.getDatabasePath(NAME).copyTo(dbCopy, true); out += dbCopy
        for (t in listOf("samples", "sleep", "steps_day", "steps_30min", "movement", "sleep_session", "session_seg")) {
            val f = File(dir, "$t.csv")
            db.rawQuery("SELECT * FROM $t ORDER BY 1, 2", null).use { c ->
                f.bufferedWriter().use { w ->
                    w.write(c.columnNames.joinToString(",")); w.write(",iso_time\r\n")
                    while (c.moveToNext()) {
                        w.write((0 until c.columnCount).joinToString(",") { if (c.isNull(it)) "" else c.getString(it) })
                        val tsCol = c.columnNames.indexOfFirst { it == "ts" || it == "start" || it == "day" }
                        w.write("," + if (tsCol >= 0) java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date(c.getLong(tsCol))) else "")
                        w.write("\r\n")
                    }
                }
            }
            out += f
        }
        return out
    }
}
