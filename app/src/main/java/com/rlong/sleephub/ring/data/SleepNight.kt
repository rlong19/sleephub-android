package com.rlong.sleephub.ring.data

import com.rlong.sleephub.ring.proto.Actigraphy
import com.rlong.sleephub.ring.proto.SleepSeg
import com.rlong.sleephub.ring.proto.SleepSession
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One night, keyed by local midnight of the morning you wake up (same key RingDb.sleep uses). */
data class SleepNight(
    val key: Long,
    val segments: List<SleepSeg>,
    /** (minute timestamp, raw 2/0x13 byte) inside the chart window. */
    val movement: List<Pair<Long, Int>>,
    val sessions: List<SleepSession>,
    val sessionSegments: Map<Long, List<SleepSeg>> = emptyMap(),
) {
    val isEmpty get() = segments.isEmpty() && sessions.isEmpty() && movement.none { it.second != 0 }
    /** Main-sleep hypnogram: 2/0E by day, else the longest non-nap session's 7/06 stages. */
    val hypnogram: List<SleepSeg> get() = segments.ifEmpty {
        sessions.filter { !it.isNap }.maxByOrNull { it.end - it.start }?.let { sessionSegments[it.start] }.orEmpty()
    }
    val start: Long? get() = hypnogram.firstOrNull()?.start ?: sessions.filter { !it.isNap }.minOfOrNull { it.start }
    val end: Long? get() = hypnogram.lastOrNull()?.end ?: sessions.filter { !it.isNap }.maxOfOrNull { it.end }
    val naps get() = sessions.filter { it.isNap }
    /** Minutes per stage: [awake, light, deep, REM]. */
    val totals: IntArray get() = IntArray(4).also { t -> hypnogram.forEach { t[it.stage.coerceIn(0, 3)] += it.minutes.toInt() } }
    val asleepMinutes get() = totals.let { it[1] + it[2] + it[3] }

    companion object {
        fun keyFor(time: Long, tz: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(tz).apply {
            timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun shiftKey(key: Long, days: Int, tz: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(tz).apply {
            timeInMillis = key; add(Calendar.DAY_OF_MONTH, days) }.timeInMillis.let { keyFor(it, tz) }
        /** Default chart window when a night has no stage data: 20:00 the evening before to 12:00. */
        fun defaultWindow(key: Long, tz: TimeZone = TimeZone.getDefault()): Pair<Long, Long> = (shiftKey(key, -1, tz) + 20 * 3600_000L) to (key + 12 * 3600_000L)
    }
}

object NightCsv {
    val STAGES = arrayOf("awake", "light", "deep", "REM")
    const val HEADER = "night,kind,start_iso,end_iso,minutes,stage,movement_raw,movement,detail"
    private fun iso(t: Long, tz: TimeZone) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = tz }.format(Date(t))
    private fun day(t: Long, tz: TimeZone) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(t))

    fun rows(n: SleepNight, tz: TimeZone = TimeZone.getDefault()): List<String> {
        val d = day(n.key, tz); val out = ArrayList<String>()
        val t = n.totals
        out += "$d,summary,${n.start?.let { iso(it, tz) } ?: ""},${n.end?.let { iso(it, tz) } ?: ""},${n.asleepMinutes},,,," +
            "awake=${t[0]} light=${t[1]} deep=${t[2]} REM=${t[3]} naps=${n.naps.size}"
        n.hypnogram.forEach { s -> out += "$d,stage,${iso(s.start, tz)},${iso(s.end, tz)},${s.minutes},${STAGES[s.stage.coerceIn(0, 3)]},,," }
        n.sessions.forEach { s -> out += "$d,${if (s.isNap) "nap" else "session"},${iso(s.start, tz)},${iso(s.end, tz)},${(s.end - s.start) / 60000},,,," +
            String.format(Locale.US, "id=%d type=%d sleep_min=%.1f efficiency=%.1f score=%.1f", s.id, s.type, s.sleepMinutes, s.efficiency, s.score) }
        n.movement.forEach { (ts, raw) -> out += "$d,movement,${iso(ts, tz)},,1,,$raw,${Actigraphy.magnitude(raw)}," }
        return out
    }
    fun write(nights: List<SleepNight>, w: Appendable, tz: TimeZone = TimeZone.getDefault()) {
        w.append(HEADER).append("\r\n")
        nights.forEach { n -> rows(n, tz).forEach { w.append(it).append("\r\n") } }
    }
    fun toString(nights: List<SleepNight>, tz: TimeZone = TimeZone.getDefault()) = StringBuilder().also { write(nights, it, tz) }.toString()
}
