package com.rlong.sleephub.ring.auto

/** Pure scheduling rules for app-triggered SpO2 (unit-tested; no Android types). */
object AutoSpo2Logic {
    const val SOURCE = "app-auto"
    const val PREF_INTERVAL = "app_spo2_interval"
    const val DEFAULT_MINUTES = 15
    val OPTIONS = listOf(0, 5, 10, 15, 20, 25, 30, 60)
    const val DEFER_MS = 60_000L
    const val MAX_DEFERS = 10
    /** Measurement attempts per tick (a BLE drop mid-measure gets one retry). */
    const val MAX_TRIES = 2
    const val CONNECT_WAIT_MS = 30_000L
    /** First tick after the interval is changed in Settings. */
    const val FIRST_TICK_MS = 30_000L

    fun valid(v: Double?): Boolean = v != null && v != 0.0 && v != 255.0 && v in 70.0..100.0

    sealed class Decision {
        object Run : Decision()
        data class Defer(val ms: Long, val why: String) : Decision()
        data class Skip(val why: String) : Decision()
    }

    /** What to do at a tick (a running sync does NOT defer: the measure pauses the sync queue and runs at once) given the ring state; [defers] = how many times this tick has already been deferred. */
    fun decide(intervalMin: Int, connected: Boolean, syncing: Boolean, measuring: Boolean, defers: Int): Decision = when {
        intervalMin <= 0 -> Decision.Skip("off")
        defers >= MAX_DEFERS -> Decision.Skip("gave up after $defers defers (" + reason(connected, measuring) + ")")
        !connected -> Decision.Defer(CONNECT_WAIT_MS, "not connected")
        measuring -> Decision.Defer(DEFER_MS, "measurement running")
        else -> Decision.Run
    }
    private fun reason(c: Boolean, m: Boolean) = when { !c -> "not connected"; m -> "measuring"; else -> "?" }

    /** Next tick: anchored to the last tick start so the cadence does not drift; never in the past. */
    fun next(lastTickMs: Long, intervalMin: Int, nowMs: Long): Long? {
        if (intervalMin <= 0) return null
        val iv = intervalMin * 60_000L
        if (lastTickMs <= 0 || lastTickMs > nowMs) return nowMs + FIRST_TICK_MS
        var t = lastTickMs + iv
        if (t <= nowMs) t = nowMs + 5_000L
        return t
    }

    /** Interval option "continuous" (explicit user choice): stream while active; alarm ticks every [CONTINUOUS_TICK_MIN] for health/sleep checks. */
    const val CONTINUOUS = -1
    const val CONTINUOUS_TICK_MIN = 10
    fun parseInterval(s: String?): Int = if (s == "continuous") CONTINUOUS else s?.toIntOrNull()?.takeIf { it in OPTIONS } ?: DEFAULT_MINUTES
    /** Alarm period in minutes (0 = off). */
    fun tickMinutes(interval: Int) = if (interval == CONTINUOUS) CONTINUOUS_TICK_MIN else interval
}
