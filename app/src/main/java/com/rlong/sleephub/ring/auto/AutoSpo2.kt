package com.rlong.sleephub.ring.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.rlong.sleephub.App
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.ble.KeepAliveService
import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.data.TrafficLog
import com.rlong.sleephub.ring.proto.Metric

/**
 * App-triggered SpO2. Each alarm tick (at the Settings interval) cheaply polls the ring's sleep signals + steps,
 * updates [SleepDetect], and — when SpO2 is wanted (sleeping / window / all day) — runs a continuous stream (if the
 * ring supports it) or one interval measurement. The ring's own 1/08 setting is not touched.
 */
object AutoSpo2 {
    private const val ACTION = "com.rlong.sleephub.ring.AUTO_SPO2_TICK"
    const val ACTION_SLEEP_TOGGLE = "com.rlong.sleephub.ring.SLEEP_TOGGLE"
    const val STREAM_SOURCE = "app-stream"
    private val main = Handler(Looper.getMainLooper())
    private var defers = 0
    private var tries = 0
    private var wake: PowerManager.WakeLock? = null
    var running = false; private set
    var lastResult = ""; private set
    val detect = SleepDetect()

    private fun p(ctx: Context) = ctx.app().prefs
    fun interval(ctx: Context) = AutoSpo2Logic.parseInterval(p(ctx).getString(AutoSpo2Logic.PREF_INTERVAL, null))
    fun enabled(ctx: Context) = interval(ctx) != 0
    fun isContinuous(ctx: Context) = interval(ctx) == AutoSpo2Logic.CONTINUOUS
    fun lastReadingMs(ctx: Context) = p(ctx).getLong("app_spo2_last_ok", 0L)
    fun lastValue(ctx: Context) = p(ctx).getFloat("app_spo2_last_val", 0f)
    fun nextMs(ctx: Context) = p(ctx).getLong("app_spo2_next", 0L)
    fun whenMode(ctx: Context) = p(ctx).getString("app_spo2_when", SpO2When.SLEEP) ?: SpO2When.SLEEP
    fun sleepingNow(ctx: Context) = p(ctx).getBoolean("sleeping_now", false)
    fun sleeping(ctx: Context) = sleepingNow(ctx) || detect.asleep
    /** -1 unknown, 0 ring does not stream, 1 streams. */
    fun streamOk(ctx: Context) = p(ctx).getInt("spo2_stream_ok", -1)
    /** What is really happening right now (shown on the SpO2 screen / notification). */
    fun statusLine(ctx: Context): String {
        val ring = App.I.ring
        return when {
            ring.measuring == Metric.SPO2 -> "Measuring SpO2 (${ring.measureRemainingSec()}s left)"
            ring.streaming -> "Measuring (continuous)"
            running || pendingStart -> "Waiting for ring" + if (ring.state != Ring.State.CONNECTED) " (not connected)" else ""
            !enabled(ctx) -> "Auto SpO2 off"
            else -> {
                val cal = java.util.Calendar.getInstance(); val nm = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                val act = SpO2When.active(whenMode(ctx), sleeping(ctx), nm, SpO2When.minutes(p(ctx).getString("spo2_win_start", "22:00")) ?: 1320, SpO2When.minutes(p(ctx).getString("spo2_win_end", "07:00")) ?: 420)
                if (act) "Next reading at " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(nextMs(ctx))) else "Idle — not sleeping"
            }
        }
    }
    private var pendingStart = false
    fun stateLine(ctx: Context): String = statusLine(ctx) + " · " + when {
        App.I.ring.streaming -> "Continuous SpO2 running (${App.I.ring.streamCount} values)"
        sleepingNow(ctx) -> "Sleeping now (manual)"
        detect.asleep -> "Asleep (auto: ${detect.reason})"
        else -> "Awake" + if (detect.reason.isNotEmpty() && detect.reason != "awake") " (${detect.reason})" else ""
    }

    fun setSleepingNow(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("sleeping_now", on).apply()
        TrafficLog.add("SYS", "sleepMode", null, if (on) "Sleeping now ON (manual)" else "Sleeping now OFF (manual)")
        val db = ctx.app().db
        if (on) db.openSleep(System.currentTimeMillis(), "manual") else if (!detect.asleep) db.closeSleep(System.currentTimeMillis())
        if (!on && !detect.asleep && App.I.ring.streamSource == STREAM_SOURCE && whenMode(ctx) != SpO2When.ALL_DAY) App.I.ring.stopStream("Sleeping now turned off")
        if (on && enabled(ctx)) {
            // same path as "Measure now": start immediately (no sleep/steps polls first — they kept GATT busy and the
            // start frame was dropped), then the normal interval follows from now
            val c = ctx.applicationContext
            p(c).edit().putLong("app_spo2_last_tick", System.currentTimeMillis()).apply(); reschedule(c, fromNow = true); acquire(c)
            ensureSteps30(); defers = 0; tries = 0; main.removeCallbacks(evaluateR); main.removeCallbacks(attemptR)
            if (isContinuous(c) && streamOk(c) != 0) { if (c.app().ring.measuring == null) c.app().ring.startStream(STREAM_SOURCE) }
            else if (c.app().ring.measuring == Metric.SPO2) TrafficLog.add("SYS", "autoSpO2", null, "Sleeping now: SpO2 already measuring")
            else { pendingStart = true; main.post(attemptR) }
            KeepAliveService.refresh(ctx); return
        }
        KeepAliveService.refresh(ctx)
        tick(ctx, manual = true)
    }

    /** (Re)arm alarm + foreground service according to the current setting. */
    fun reschedule(ctx: Context, fromNow: Boolean = false) {
        val c = ctx.applicationContext
        val iv = AutoSpo2Logic.tickMinutes(interval(c))
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c))
        if (iv <= 0) {
            p(c).edit().putLong("app_spo2_next", 0L).apply()
            if (App.I.ring.streamSource == STREAM_SOURCE) App.I.ring.stopStream("auto SpO2 turned off")
            if (!c.app().ring.stayConnectedBg()) KeepAliveService.stop(c) else KeepAliveService.refresh(c)
            return
        }
        val now = System.currentTimeMillis()
        val last = if (fromNow) 0L else p(c).getLong("app_spo2_last_tick", 0L)
        val at = AutoSpo2Logic.next(last, iv, now)!!
        setAlarm(c, am, at)
        p(c).edit().putLong("app_spo2_next", at).apply()
        KeepAliveService.start(c)
    }
    private fun setAlarm(c: Context, am: AlarmManager, at: Long) {
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c)) }
        TrafficLog.add("SYS", "autoSpO2", null, "next tick ${fmt(at)} (${if (exact) "exact" else "inexact"})")
    }
    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 7, Intent(c, Receiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** One tick: arm the next alarm, poll sleep signals, then decide what to measure. */
    fun tick(ctx: Context, manual: Boolean = false) {
        val c = ctx.applicationContext
        if (!enabled(c) && !manual) return
        p(c).edit().putLong("app_spo2_last_tick", System.currentTimeMillis()).apply()
        if (enabled(c)) reschedule(c)
        acquire(c)
        val ring = c.app().ring
        if (ring.state != Ring.State.CONNECTED) ring.tryAutoConnect() else if (ring.measuring == null) { ring.pollSleep(); ring.readSteps() }
        main.removeCallbacks(evaluateR); main.postDelayed(evaluateR, 6_000)
    }
    private val evaluateR = Runnable { evaluate() }

    /** While asleep: read FDD1 live steps every 30 s (GATT read on the open link, no LED); any step = movement event for the arousal proxy. */
    private var stepsPrev: Int? = null
    private val steps30 = object : Runnable { override fun run() {
        val c = App.I; val ring = c.ring
        if (!sleeping(c) || !enabled(c)) { stepsPrev = null; return }
        if (ring.state == Ring.State.CONNECTED) ring.readSteps()
        main.postDelayed({
            val s = ring.live[Metric.STEPS]?.toInt()
            if (s != null && stepsPrev != null && s > stepsPrev!!) {
                c.db.addSamples(listOf(com.rlong.sleephub.ring.proto.Sample(Metric.STEPS, System.currentTimeMillis(), (s - stepsPrev!!).toDouble(), "app-30s")), false)
                TrafficLog.add("SYS", "sleepSteps", null, "+${s - stepsPrev!!} steps in 30 s while asleep")
            }
            if (s != null) stepsPrev = s
        }, 2_000)
        main.postDelayed(this, 30_000)
    } }
    private fun ensureSteps30() { main.removeCallbacks(steps30); if (sleeping(App.I)) main.post(steps30) }
    private fun evaluate() {
        val c = App.I; val ring = c.ring; val now = System.currentTimeMillis()
        val hr = c.db.latest(Metric.HEART_RATE, false)?.takeIf { now - it.time < 30 * 60_000L }?.value
        val changed = detect.update(SleepDetect.Input(now, ring.live[Metric.STEPS]?.toInt(), ring.ringSleepState, ring.ringSleepStateAt,
            ring.lastStageEnd, ring.lastStage, hr, c.db.restingHr(now)))
        if (changed) {
            TrafficLog.add("SYS", "sleepMode", null, (if (detect.asleep) "ASLEEP (auto): " else "AWAKE (auto): ") + detect.reason)
            if (detect.asleep) c.db.openSleep(now, detect.reason) else if (!sleepingNow(c)) c.db.closeSleep(now)
        }
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val ws = SpO2When.minutes(p(c).getString("spo2_win_start", "22:00")) ?: 22 * 60
        val we = SpO2When.minutes(p(c).getString("spo2_win_end", "07:00")) ?: 7 * 60
        val sleeping = sleeping(c)
        if (changed || sleeping) ensureSteps30()
        val active = SpO2When.active(whenMode(c), sleeping, nowMin, ws, we)
        KeepAliveService.refresh(c)
        if (!active) {
            if (ring.streamSource == STREAM_SOURCE) ring.stopStream("left sleep/window")
            lastResult = "idle (${stateLine(c)})"; TrafficLog.add("SYS", "autoSpO2", null, lastResult); release(); return
        }
        if (ring.streaming && ring.streamSource != STREAM_SOURCE) { lastResult = "test stream running"; release(); return }
        if (ring.streaming) {
            // health check: a stream that delivered almost nothing in 3 min → ring does not stream; fall back to interval
            if (now - ring.streamStartedAt > 3 * 60_000L && ring.streamCount <= 1) {
                p(c).edit().putInt("spo2_stream_ok", 0).apply(); ring.stopStream("ring delivered ≤1 value in 3 min — interval mode")
            } else {
                if (ring.streamCount >= 5 && streamOk(c) != 1) p(c).edit().putInt("spo2_stream_ok", 1).apply()
                lastResult = "continuous: ${ring.streamCount} values"; release(); return
            }
        } else if (isContinuous(c) && streamOk(c) != 0 && ring.state == Ring.State.CONNECTED && ring.measuring == null) {
            ring.startStream(STREAM_SOURCE); lastResult = "continuous started"; release(); return
        }
        defers = 0; tries = 0; main.removeCallbacks(attemptR); main.post(attemptR)
    }
    private val attemptR = Runnable { attempt() }
    private fun attempt() {
        val c = App.I
        val ring = c.ring
        if (running) return
        val d = AutoSpo2Logic.decide(maxOf(AutoSpo2Logic.tickMinutes(interval(c)), 1), ring.state == Ring.State.CONNECTED, ring.fullSyncRunning, ring.measuring != null, defers)
        when (d) {
            is AutoSpo2Logic.Decision.Run -> {
                running = true; pendingStart = false; tries++
                TrafficLog.add("SYS", "autoSpO2", null, "start measurement" + if (ring.fullSyncRunning) " (alongside running sync)" else "")
                ring.onMeasureFinished = { m, v, src -> if (m == Metric.SPO2 && src == AutoSpo2Logic.SOURCE) done(v) }
                ring.startMeasure(Metric.SPO2, AutoSpo2Logic.SOURCE)
                main.removeCallbacks(watchdog); main.postDelayed(watchdog, com.rlong.sleephub.ring.proto.measureTimeoutMs(Metric.SPO2) + 20_000L)
            }
            is AutoSpo2Logic.Decision.Defer -> {
                defers++
                TrafficLog.add("SYS", "autoSpO2", null, "defer ${d.ms / 1000}s: ${d.why} (#$defers)")
                if (ring.state == Ring.State.DISCONNECTED) ring.tryAutoConnect()
                main.postDelayed(attemptR, d.ms)
            }
            is AutoSpo2Logic.Decision.Skip -> { pendingStart = false; lastResult = "skipped: ${d.why}"; TrafficLog.add("SYS", "autoSpO2", null, lastResult); release() }
        }
    }
    private val watchdog = Runnable { if (running) { TrafficLog.add("SYS", "autoSpO2", null, "watchdog: measurement never finished"); done(null) } }
    private fun done(v: Double?) {
        val c = App.I
        main.removeCallbacks(watchdog)
        running = false; pendingStart = false; c.ring.onMeasureFinished = null
        if (v == null && tries < AutoSpo2Logic.MAX_TRIES && enabled(c)) {
            lastResult = "no value (try $tries) — retrying"; TrafficLog.add("SYS", "autoSpO2", null, lastResult)
            main.postDelayed(attemptR, AutoSpo2Logic.CONNECT_WAIT_MS); return
        }
        lastResult = if (AutoSpo2Logic.valid(v)) {
            c.prefs.edit().putLong("app_spo2_last_ok", System.currentTimeMillis()).putFloat("app_spo2_last_val", v!!.toFloat()).apply()
            "saved ${v.toInt()}%"
        } else "discarded ${v ?: "no value"}"
        TrafficLog.add("SYS", "autoSpO2", null, lastResult)
        KeepAliveService.refresh(c)
        release()
    }
    private fun acquire(c: Context) {
        release()
        wake = c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "darings:autoSpO2").apply { acquire(4 * 60_000L) }
    }
    private fun release() { try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}; wake = null }
    fun fmt(t: Long) = if (t <= 0) "—" else java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US).format(java.util.Date(t))

    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION -> { try { KeepAliveService.start(c) } catch (_: Exception) {}; tick(c) }
                ACTION_SLEEP_TOGGLE -> setSleepingNow(c, !sleepingNow(c))
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> if (enabled(c)) reschedule(c)
            }
        }
    }
}
