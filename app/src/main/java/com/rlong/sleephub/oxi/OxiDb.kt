package com.rlong.sleephub.oxi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 1 Hz oximeter storage. ts = epoch ms INTEGER. All range bounds are bound through CAST(? AS INTEGER) so SQLite
 * always compares numbers with numbers (avoids the earlier text-vs-integer bound bug); see [RANGE_SQL].
 */
class OxiDb(ctx: Context, name: String? = "oximeter.db") : SQLiteOpenHelper(ctx, name, null, 3) {
    companion object { const val RANGE_SQL = "ts >= CAST(? AS INTEGER) AND ts <= CAST(? AS INTEGER)" }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE oxi (ts INTEGER NOT NULL, spo2 INTEGER, pulse INTEGER, source TEXT NOT NULL, PRIMARY KEY(ts, source))")
        db.execSQL("CREATE INDEX oxi_ts ON oxi(ts)"); v2(db)
    }
    private fun v2(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS oxi_segment (start INTEGER NOT NULL, length INTEGER NOT NULL, item INTEGER, saved INTEGER, rows INTEGER, PRIMARY KEY(start, length))")
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) v2(db); if (o < 3) lastCleaned = v3(db) }
    /** Rows cleaned by the v3 migration in this process (-1 = migration not run now). */
    var lastCleaned = -1; private set
    /** v3: null out sentinel/out-of-range values stored by earlier versions (finger-off pulse 255 etc.); drop rows left empty. */
    private fun v3(db: SQLiteDatabase): Int {
        var n = 0
        db.compileStatement("UPDATE oxi SET pulse=NULL WHERE pulse IS NOT NULL AND (pulse>${OxiValid.PULSE.last} OR pulse<${OxiValid.PULSE.first})").use { n += it.executeUpdateDelete() }
        db.compileStatement("UPDATE oxi SET spo2=NULL WHERE spo2 IS NOT NULL AND (spo2>${OxiValid.SPO2.last} OR spo2<${OxiValid.SPO2.first})").use { n += it.executeUpdateDelete() }
        android.util.Log.i("OxiDb", "v3 migration cleaned $n values")
        return n
    }

    /** Device-history segment already downloaded? Key = (start ms, length s). */
    fun hasSegment(start: Long, length: Int): Boolean = readableDatabase.compileStatement("SELECT COUNT(*) FROM oxi_segment WHERE start=? AND length=?").run { bindLong(1, start); bindLong(2, length.toLong()); simpleQueryForLong() } > 0
    fun markSegment(start: Long, length: Int, item: Int, rows: Int) { writableDatabase.execSQL("INSERT OR REPLACE INTO oxi_segment(start, length, item, saved, rows) VALUES (?,?,?,?,?)",
        arrayOf<Any>(start, length.toLong(), item.toLong(), System.currentTimeMillis(), rows.toLong())) }

    /** Insert [l] tagged [source], skipping any second already covered (±500 ms) by a row from another source (BLE/CSV). Returns rows inserted. */
    fun addDedup(l: List<OxiReading>, source: String): Int { if (l.isEmpty()) return 0
        val db = writableDatabase; var n = 0; db.beginTransaction()
        try { val q = db.compileStatement("SELECT COUNT(*) FROM oxi WHERE ts >= ? AND ts <= ? AND source <> ?")
            val st = db.compileStatement("INSERT OR REPLACE INTO oxi(ts, spo2, pulse, source) VALUES (?,?,?,?)")
            for (r in l.map { it.sanitized() }) { if (r.spo2 == null && r.pulse == null) continue
                q.clearBindings(); q.bindLong(1, r.time - 500); q.bindLong(2, r.time + 499); q.bindString(3, source)
                if (q.simpleQueryForLong() > 0) continue
                st.clearBindings(); st.bindLong(1, r.time)
                if (r.spo2 != null) st.bindLong(2, r.spo2.toLong()) else st.bindNull(2)
                if (r.pulse != null) st.bindLong(3, r.pulse.toLong()) else st.bindNull(3)
                st.bindString(4, source); st.executeInsert(); n++ }
            db.setTransactionSuccessful() } finally { db.endTransaction() }
        return n }

    fun add(l: List<OxiReading>, source: String) { if (l.isEmpty()) return
        val db = writableDatabase; db.beginTransaction()
        try { val st = db.compileStatement("INSERT OR REPLACE INTO oxi(ts, spo2, pulse, source) VALUES (?,?,?,?)")
            l.map { it.sanitized() }.forEach { r -> st.clearBindings(); st.bindLong(1, r.time)
                if (r.spo2 != null) st.bindLong(2, r.spo2.toLong()) else st.bindNull(2)
                if (r.pulse != null) st.bindLong(3, r.pulse.toLong()) else st.bindNull(3)
                st.bindString(4, source); st.executeInsert() }
            db.setTransactionSuccessful() } finally { db.endTransaction() } }

    fun range(from: Long, to: Long): List<OxiReading> =
        readableDatabase.rawQuery("SELECT ts, spo2, pulse FROM oxi WHERE $RANGE_SQL ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            val l = ArrayList<OxiReading>(c.count)
            while (c.moveToNext()) l += OxiReading(c.getLong(0), OxiValid.spo2(if (c.isNull(1)) null else c.getInt(1)), OxiValid.pulse(if (c.isNull(2)) null else c.getInt(2))); l }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM oxi", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
}
