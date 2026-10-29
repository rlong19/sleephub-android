package com.rlong.sleephub.ring.auto

/**
 * Decides "asleep?": primary = stillness (no steps for [STILL_MS], HR ≤ resting + [HR_MARGIN] when known);
 * secondary = ring 2/30 / 7/0A sleep-state reply or today's 2/0E hypnogram growing. Exit: sustained steps. Pure (unit-tested).
 */
class SleepDetect {
    companion object {
        const val STILL_MS = 20 * 60_000L
        const val HR_MARGIN = 5.0
        const val RING_STATE_FRESH_MS = 15 * 60_000L
        const val HYPNO_RECENT_MS = 25 * 60_000L
        const val WAKE_STEPS_ONE = 40
        const val WAKE_STEPS_EACH = 10
    }
    var asleep = false; private set
    var since = 0L; private set
    var reason = ""; private set
    private var lastSteps: Int? = null
    private var stillSince = -1L
    private var movingChecks = 0

    data class Input(val now: Long, val steps: Int?, val ringState: Int?, val ringStateAt: Long,
                     val lastStageEnd: Long, val lastStage: Int?, val hr: Double?, val restingHr: Double?)

    /** Returns true if the state changed. */
    fun update(i: Input): Boolean {
        val delta = if (i.steps != null && lastSteps != null) (i.steps - lastSteps!!).coerceAtLeast(0) else null
        if (i.steps != null) {
            if (lastSteps == null || (delta ?: 0) > 0) stillSince = i.now
            if (lastSteps == null) stillSince = i.now
            lastSteps = i.steps
        }
        movingChecks = if ((delta ?: 0) >= WAKE_STEPS_EACH) movingChecks + 1 else 0
        val ringFresh = i.ringState != null && i.now - i.ringStateAt <= RING_STATE_FRESH_MS
        val hypno = i.lastStage != null && i.lastStage != 0 && i.now - i.lastStageEnd <= HYPNO_RECENT_MS
        val still = stillSince >= 0 && i.now - stillSince >= STILL_MS
        val hrOk = i.hr == null || i.restingHr == null || i.hr <= i.restingHr + HR_MARGIN
        val wake = (delta ?: 0) >= WAKE_STEPS_ONE || movingChecks >= 2
        val (want, why) = when {
            asleep && wake -> false to "steps resumed (+$delta)"
            still && hrOk -> true to "still: no steps ${(i.now - stillSince) / 60000} min" + (i.hr?.let { ", HR ${it.toInt()} ≤ rest ${i.restingHr?.toInt()}+5" } ?: "")
            ringFresh && i.ringState!! > 0 -> true to "ring sleep-state=${i.ringState}"
            hypno -> true to "ring hypnogram stage ${i.lastStage} ended ${(i.now - i.lastStageEnd) / 60000} min ago"
            asleep -> true to reason
            else -> false to "awake"
        }
        if (want == asleep) return false
        asleep = want; since = i.now; reason = why; return true
    }
}

/** When auto SpO2 may run. */
object SpO2When {
    const val SLEEP = "sleep"; const val WINDOW = "window"; const val ALL_DAY = "allday"
    /** Minutes since midnight for "HH:mm"; null if unparsable. */
    fun minutes(s: String?): Int? = s?.trim()?.split(":")?.takeIf { it.size == 2 }?.let { (h, m) -> val hh = h.toIntOrNull(); val mm = m.toIntOrNull()
        if (hh != null && mm != null && hh in 0..23 && mm in 0..59) hh * 60 + mm else null }
    fun inWindow(nowMin: Int, start: Int, end: Int) = if (start <= end) nowMin in start until end else nowMin >= start || nowMin < end
    fun active(mode: String, sleeping: Boolean, nowMin: Int, start: Int, end: Int) = when (mode) {
        ALL_DAY -> true
        WINDOW -> inWindow(nowMin, start, end) || sleeping
        else -> sleeping
    }
}

/** ODI-style desaturation finder: drop ≥ [DROP] points below the median of the previous [BASE_MS], lasting ≥ [MIN_MS]. Screening aid only. */
object Dips {
    const val DROP = 4.0; const val BASE_MS = 120_000L; const val MIN_MS = 10_000L
    data class Dip(val start: Long, val end: Long, val nadir: Double, val baseline: Double)
    fun find(sorted: List<Pair<Long, Double>>): List<Dip> {
        val out = ArrayList<Dip>()
        var i = 0; var lo = 0
        while (i < sorted.size) {
            val (t, v) = sorted[i]
            while (lo < i && sorted[lo].first < t - BASE_MS) lo++
            val base = sorted.subList(lo, i).map { it.second }.sorted()
            if (base.size >= 3) {
                val b = base[base.size / 2]
                if (v <= b - DROP) {
                    var j = i; var nadir = v
                    while (j + 1 < sorted.size && sorted[j + 1].second <= b - DROP && sorted[j + 1].first - sorted[j].first <= 30_000L) { j++; nadir = minOf(nadir, sorted[j].second) }
                    val end = if (j + 1 < sorted.size) sorted[j + 1].first else sorted[j].first
                    if (end - t >= MIN_MS) out += Dip(t, end, nadir, b)
                    i = j + 1; continue
                }
            }
            i++
        }
        return out
    }
}

/**
 * Arousal-burst proxy (no extra ring battery): inside sleep periods, a per-minute movement spike after ≥ [STILL_MIN]
 * still minutes; flagged "HR+" when the 5-min HR within ±[HR_TOL_MS] is ≥ [HR_RISE]× the median of the prior 30 min.
 * Screening proxy only — not a diagnosis.
 */
object Arousals {
    const val STILL_MIN = 5; const val MIN_MAG = 1; const val HR_RISE = 1.10; const val HR_TOL_MS = 5 * 60_000L
    data class Event(val time: Long, val magnitude: Int, val hrRise: Boolean)
    /** [move] = (minute ts, magnitude ≥ 0) sorted; [hr] = (ts, bpm) sorted. */
    fun find(move: List<Pair<Long, Int>>, hr: List<Pair<Long, Double>>, periods: List<Pair<Long, Long>>): List<Event> {
        val out = ArrayList<Event>()
        for ((s, e) in periods) {
            var still = 0; var prevT = Long.MIN_VALUE; var inBurst = false
            for ((t, mag) in move) {
                if (t < s || t > e) continue
                if (prevT != Long.MIN_VALUE && t - prevT > 90_000L) { still = 0; inBurst = false }   // gap in data: restart
                prevT = t
                if (mag < MIN_MAG) { still++; inBurst = false; continue }
                if (!inBurst && still >= STILL_MIN) {
                    val base = hr.filter { it.first in (t - 30 * 60_000L) until (t - HR_TOL_MS) }.map { it.second }.sorted()
                    val near = hr.filter { kotlin.math.abs(it.first - t) <= HR_TOL_MS }.maxOfOrNull { it.second }
                    val rise = base.size >= 2 && near != null && near >= base[base.size / 2] * HR_RISE
                    out += Event(t, mag, rise)
                }
                inBurst = true; still = 0
            }
        }
        return out.distinctBy { it.time }
    }
    fun perHour(n: Int, periods: List<Pair<Long, Long>>): Double {
        val h = periods.sumOf { it.second - it.first } / 3600_000.0
        return if (h <= 0) 0.0 else n / h
    }
}
