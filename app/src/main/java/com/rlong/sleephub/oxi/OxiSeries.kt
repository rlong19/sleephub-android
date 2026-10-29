package com.rlong.sleephub.oxi

/** Pure helpers for the live graph (unit-tested). */
object OxiSeries {
    const val GAP_MS = 3_000L
    /** Split readings within [now-windowMs, now] into continuous segments of (time, value). A null value (finger off)
     *  or a time gap > [gapMs] (disconnect) ends the segment, so gaps are drawn as breaks, never as zeros. */
    fun segments(rs: List<OxiReading>, now: Long, windowMs: Long, value: (OxiReading) -> Int?, gapMs: Long = GAP_MS): List<List<Pair<Long, Double>>> {
        val out = ArrayList<List<Pair<Long, Double>>>(); var cur = ArrayList<Pair<Long, Double>>(); var lastT = Long.MIN_VALUE
        for (r in rs) {
            if (r.time < now - windowMs || r.time > now + 1000) continue
            val v = value(r)
            if (v == null || (cur.isNotEmpty() && r.time - lastT > gapMs)) { if (cur.isNotEmpty()) out += cur; cur = ArrayList() }
            if (v != null) { cur += r.time to v.toDouble(); lastT = r.time }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
    /** Autoscale range for pulse: min/max of visible values padded by 5 bpm, at least 20 bpm wide. */
    fun pulseRange(segs: List<List<Pair<Long, Double>>>): Pair<Double, Double> {
        val vs = segs.flatten().map { it.second }
        if (vs.isEmpty()) return 50.0 to 100.0
        var lo = vs.min() - 5; var hi = vs.max() + 5
        if (hi - lo < 20) { val m = (hi + lo) / 2; lo = m - 10; hi = m + 10 }
        return lo.coerceAtLeast(0.0) to hi
    }
}
