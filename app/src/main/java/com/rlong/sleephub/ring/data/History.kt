package com.rlong.sleephub.ring.data

import com.rlong.sleephub.ring.proto.Actigraphy
import com.rlong.sleephub.ring.proto.Metric
import com.rlong.sleephub.ring.proto.Sample
import com.rlong.sleephub.ring.proto.SleepSeg
import java.util.TimeZone

/** Pixel <-> time mapping shared by every History chart (same insets ⇒ the cursor lines up everywhere). */
data class TimeAxis(val t0: Long, val t1: Long, val padL: Float = 0.10f, val padR: Float = 0.03f) {
    private val span get() = (t1 - t0).coerceAtLeast(1)
    fun left(width: Int) = width * padL
    fun plotWidth(width: Int) = width * (1 - padL - padR)
    fun x(t: Long, width: Int): Float = left(width) + plotWidth(width) * ((t - t0).toDouble() / span).toFloat()
    /** Inverse of [x], clamped to the window. */
    fun timeAt(x: Float, width: Int): Long {
        val f = ((x - left(width)) / plotWidth(width).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return t0 + (f.toDouble() * span).toLong()
    }
}

/**
 * Shared History zoom: visible window [t0]..[t1] inside the full page [full0]..[full1]; never narrower than [minSpan]
 * (60 s, enough to see individual 1 Hz oximeter seconds). Pure logic, unit-tested.
 */
class ZoomWindow(val full0: Long, val full1: Long, val minSpan: Long = 60_000L) {
    var t0 = full0; private set
    var t1 = full1; private set
    val span get() = t1 - t0
    val fullSpan get() = (full1 - full0).coerceAtLeast(1)
    val zoomed get() = t0 > full0 || t1 < full1
    val axis get() = TimeAxis(t0, t1)
    /** Set the window, keeping its span within [minSpan]..full and shifting it back inside the page. */
    fun set(a: Long, b: Long) {
        val sp = (b - a).coerceIn(minOf(minSpan, fullSpan), fullSpan)
        var s0 = a + ((b - a) - sp) / 2
        s0 = s0.coerceIn(full0, full1 - sp)
        t0 = s0; t1 = s0 + sp
    }
    /** Zoom by [scale] (>1 = zoom in) keeping time [focus] at the same relative position. */
    fun zoom(scale: Double, focus: Long) {
        if (scale <= 0.0) return
        val f = focus.coerceIn(t0, t1); val newSpan = (span / scale).toLong().coerceIn(minOf(minSpan, fullSpan), fullSpan)
        val frac = if (span > 0) (f - t0).toDouble() / span else 0.5
        val a = f - (frac * newSpan).toLong()
        set(a, a + newSpan)
    }
    fun pan(dt: Long) = set(t0 + dt, t1 + dt)
    fun reset() { t0 = full0; t1 = full1 }
}

/**
 * Retains the History zoom across view rebuilds/refreshes: same page range → same [ZoomWindow]; same page start with a
 * grown end (Today (partial) refreshed later) → the zoomed span carries over; a new day/window mode → fresh full-day window.
 */
object HistoryZoom {
    private var cur: ZoomWindow? = null
    @Synchronized fun forPage(t0: Long, t1: Long): ZoomWindow {
        val c = cur
        if (c != null && c.full0 == t0 && c.full1 == t1) return c
        val n = ZoomWindow(t0, t1)
        if (c != null && c.full0 == t0 && c.zoomed) n.set(c.t0, c.t1)
        cur = n; return n
    }
}

enum class WindowMode { NOON, MIDNIGHT }

object HistoryWindow {
    /**
     * Page [key] = local midnight of date D. NOON: D-1 12:00 → D 12:00 (the night ending on the morning of D sits in the
     * middle). MIDNIGHT: D 00:00 → D+1 00:00. Calendar arithmetic, so DST days are 23/25 h.
     */
    fun of(key: Long, mode: WindowMode, tz: TimeZone = TimeZone.getDefault()): Pair<Long, Long> = when (mode) {
        WindowMode.NOON -> noon(SleepNight.shiftKey(key, -1, tz), tz) to noon(key, tz)
        WindowMode.MIDNIGHT -> key to SleepNight.shiftKey(key, 1, tz)
    }
    /** Newest page key: in NOON mode the in-progress window (today 12:00 → tomorrow 12:00) is keyed by tomorrow. */
    fun maxKey(now: Long, mode: WindowMode, tz: TimeZone = TimeZone.getDefault()): Long =
        SleepNight.keyFor(now, tz).let { if (mode == WindowMode.NOON) SleepNight.shiftKey(it, 1, tz) else it }
    /** Key of the synthetic "Today (partial)" entry: one past the newest real page. */
    fun partialKey(now: Long, mode: WindowMode, tz: TimeZone = TimeZone.getDefault()) = SleepNight.shiftKey(maxKey(now, mode, tz), 1, tz)
    /** Today (partial): local midnight → now. */
    fun partialWindow(now: Long, tz: TimeZone = TimeZone.getDefault()) = SleepNight.keyFor(now, tz) to maxOf(now, SleepNight.keyFor(now, tz) + 60_000L)
    /** Page containing time [t]. */
    fun keyContaining(t: Long, mode: WindowMode, tz: TimeZone = TimeZone.getDefault()): Long {
        val d = SleepNight.keyFor(t, tz)
        return if (mode == WindowMode.NOON && t >= of(SleepNight.shiftKey(d, 1, tz), mode, tz).first) SleepNight.shiftKey(d, 1, tz) else d
    }
    /** Default page: the one holding the newest stored point (so after-noon readings open tonight's window). */
    fun defaultKey(newest: Long?, now: Long, mode: WindowMode, tz: TimeZone = TimeZone.getDefault()): Long = when {
        newest != null && newest >= SleepNight.keyFor(now, tz) -> partialKey(now, mode, tz)
        newest != null -> keyContaining(minOf(newest, now), mode, tz)
        else -> partialKey(now, mode, tz)
    }
    private fun noon(dayKey: Long, tz: TimeZone) = java.util.Calendar.getInstance(tz).apply { timeInMillis = dayKey; set(java.util.Calendar.HOUR_OF_DAY, 12) }.timeInMillis
}

object Nearest {
    const val MIN = 60_000L
    /** Lookup tolerance per series: ±5 min for 5-minute tables, ±15 min for sparse SpO2, ±1 min for per-minute movement. */
    fun tolerance(m: Metric?): Long = when (m) { null -> MIN; Metric.SPO2 -> 15 * MIN; else -> 5 * MIN }

    /** Nearest element of time-sorted [times] to [t] within ±[tol]; returns its index or -1. */
    fun index(times: LongArray, t: Long, tol: Long): Int {
        if (times.isEmpty()) return -1
        var lo = 0; var hi = times.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (times[mid] < t) lo = mid + 1 else hi = mid }
        var best = -1; var bd = Long.MAX_VALUE
        for (i in intArrayOf(lo - 1, lo)) if (i in times.indices) { val d = kotlin.math.abs(times[i] - t); if (d <= tol && d < bd) { best = i; bd = d } }
        return best
    }
    fun sample(sorted: List<Sample>, t: Long, tol: Long): Sample? =
        index(LongArray(sorted.size) { sorted[it].time }, t, tol).takeIf { it >= 0 }?.let { sorted[it] }
    /** Stage covering [t] (start inclusive, end exclusive) or null. */
    fun stage(segs: List<SleepSeg>, t: Long): Int? = segs.firstOrNull { t >= it.start && t < it.end }?.stage
    fun movement(sorted: List<Pair<Long, Int>>, t: Long, tol: Long = MIN): Int? =
        index(LongArray(sorted.size) { sorted[it].first }, t, tol).takeIf { it >= 0 }?.let { Actigraphy.magnitude(sorted[it].second) }
}

/** Everything one History page shows. */
data class HistoryPage(
    val key: Long, val t0: Long, val t1: Long,
    val segments: List<SleepSeg>, val movement: List<Pair<Long, Int>>,
    val vitals: VitalsDay, val night: SleepNight,
) {
    /** Scrub readout values; null means "—". */
    data class Readout(val time: Long, val stage: Int?, val movement: Int?, val values: Map<Metric, Sample?>)
    // v0.3.12: sorted lists + time arrays built once per page (the scrub readout used to re-sort every metric per finger move)
    private val sorted by lazy { Vitals.METRICS.associateWith { m -> vitals.of(m).sortedBy { it.time }.let { l -> l to LongArray(l.size) { l[it].time } } } }
    private val moveSorted by lazy { movement.sortedBy { it.first }.let { l -> l to LongArray(l.size) { l[it].first } } }
    fun readout(t: Long): Readout = Readout(t, Nearest.stage(segments, t),
        Nearest.index(moveSorted.second, t, Nearest.MIN).takeIf { it >= 0 }?.let { Actigraphy.magnitude(moveSorted.first[it].second) },
        Vitals.METRICS.associateWith { m -> sorted[m]!!.let { (l, ts) -> Nearest.index(ts, t, Nearest.tolerance(m)).takeIf { it >= 0 }?.let { l[it] } } })
}
