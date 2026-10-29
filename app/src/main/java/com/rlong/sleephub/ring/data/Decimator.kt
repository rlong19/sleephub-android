package com.rlong.sleephub.ring.data

/**
 * Draw-time decimation for History charts (v0.3.12). Stored data is never touched: every call works on the full
 * per-second series and only reduces what is drawn for the visible window [t0]..[t1] split into [cols] pixel columns.
 *
 * - Visible points (plus one neighbour each side) <= [cols] → raw passthrough (lo == hi == value, t = sample time).
 * - Otherwise each column keeps the min and max of its samples (spikes and dips survive), t = first sample time in column.
 * - Gaps: a NaN value, or a time jump > [maxGap] between consecutive valid samples, ends the current run. Runs are never joined.
 * Pure Kotlin, unit-tested (DecimatorTest).
 */
object Decimator {
    class Pt(val t: Long, var lo: Float, var hi: Float) {
        override fun toString() = "Pt($t,$lo,$hi)"
    }
    class Result(val raw: Boolean, val runs: List<List<Pt>>) {
        val pointCount get() = runs.sumOf { it.size }
    }

    /** First index with times[i] >= t. */
    fun lowerBound(times: LongArray, t: Long): Int {
        var lo = 0; var hi = times.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (times[m] < t) lo = m + 1 else hi = m }
        return lo
    }

    fun decimate(times: LongArray, vals: FloatArray, t0: Long, t1: Long, cols: Int, maxGap: Long = Long.MAX_VALUE): Result {
        require(times.size == vals.size)
        if (times.isEmpty() || cols <= 0 || t1 <= t0) return Result(true, emptyList())
        val i0 = (lowerBound(times, t0) - 1).coerceAtLeast(0)
        val i1 = (lowerBound(times, t1 + 1)).coerceAtMost(times.size - 1)   // one past the window (neighbour)
        if (i1 < i0) return Result(true, emptyList())
        val raw = (i1 - i0 + 1) <= cols
        val span = (t1 - t0).toDouble()
        val runs = ArrayList<List<Pt>>()
        var run = ArrayList<Pt>()
        var prevT = Long.MIN_VALUE; var curCol = Long.MIN_VALUE
        fun close() { if (run.isNotEmpty()) { runs += run; run = ArrayList() }; curCol = Long.MIN_VALUE }
        for (i in i0..i1) {
            val v = vals[i]; val t = times[i]
            if (v.isNaN()) { close(); prevT = Long.MIN_VALUE; continue }
            if (prevT != Long.MIN_VALUE && t - prevT > maxGap) close()
            prevT = t
            if (raw) { run += Pt(t, v, v); continue }
            val col = Math.floor((t - t0) / span * cols).toLong()
            if (col == curCol && run.isNotEmpty()) { val p = run[run.size - 1]; if (v < p.lo) p.lo = v; if (v > p.hi) p.hi = v }
            else { run += Pt(t, v, v); curCol = col }
        }
        close()
        return Result(raw, runs)
    }
}

/** Immutable in-memory series (time-sorted, NaN = gap) built once per data load; shared by charts and scrub readouts. */
class Series(val times: LongArray, val vals: FloatArray) {
    val size get() = times.size
    val min: Float = vals.filter { !it.isNaN() }.minOrNull() ?: Float.NaN
    val max: Float = vals.filter { !it.isNaN() }.maxOrNull() ?: Float.NaN
    val isEmpty get() = min.isNaN()
    companion object {
        val EMPTY = Series(LongArray(0), FloatArray(0))
        fun <T> of(l: List<T>, time: (T) -> Long, value: (T) -> Number?): Series {
            val s = l.sortedBy(time)
            return Series(LongArray(s.size) { time(s[it]) }, FloatArray(s.size) { value(s[it])?.toFloat() ?: Float.NaN })
        }
    }
}
