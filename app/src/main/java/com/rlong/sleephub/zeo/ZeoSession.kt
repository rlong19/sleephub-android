package com.rlong.sleephub.zeo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import com.rlong.sleephub.core.Timeline
import com.rlong.sleephub.core.TrafficLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owns the Zeo link (real RFCOMM or DEMO), implements the official app's message flow, and feeds the timeline. */
class ZeoSession(private val ctx: Context, val log: TrafficLogger, private val timeline: Timeline) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }
    var state = State.DISCONNECTED; private set
    var status = "Not connected"; private set
    var demo = false; private set
    var info: ZeoMsg.Starting? = null; private set
    var hbState: ZeoMsg.State? = null; private set
    var sleep: ZeoMsg.Sleep? = null; private set
    var lastEvent: String = ""; private set
    /** phoneNow − headbandNow (official app's clock_offset), learned from every TIME_REPORT and persisted per headband MAC. */
    var clockOffsetMs: Long? = null; private set
    private fun offKey(mac: String?) = "zeo_clock_offset_${mac ?: "?"}"
    private fun loadOffset(mac: String?) { clockOffsetMs = if (prefs.contains(offKey(mac))) prefs.getLong(offKey(mac), 0L) else null
        clockOffsetMs?.let { o -> log.add("SYS", "clockOffset", null, "loaded ${o} ms for $mac"); try { com.rlong.sleephub.App.I.zeoDb.remap(o) } catch (_: Exception) {} } }
    var lastRx = 0L; private set
    val listeners = ArrayList<() -> Unit>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var link: ZeoTransport? = null
    private val asm = Hmsg.Assembler { why, raw -> log.add("RX", "BAD", raw, why) }
    private var gotFirstState = false
    private var emittedEpochs = 0; private var emittedNight = -1L
    private var tsState = 0; private var tsSend = 0L; private var tsFixes = 0
    private val src get() = if (demo) "zeo-DEMO" else "zeo"

    val savedAddress: String? get() = prefs.getString("zeo_mac", null)
    val savedName: String? get() = prefs.getString("zeo_name", null)
    val pollSec: Int get() = prefs.getString("zeo_poll_sec", "60")?.toIntOrNull() ?: 60
    val autoTimeSync: Boolean get() = prefs.getBoolean("zeo_time_sync", true)

    private val poll = object : Runnable { override fun run() {
        if (state == State.CONNECTED && pollSec > 0) { send(Hmsg.timeQuery(), "poll: clock offset"); send(Hmsg.command(Hmsg.Cmd.QUERY_STATE), "poll"); send(Hmsg.command(Hmsg.Cmd.SLEEP_SEND), "poll") }
        main.postDelayed(this, (pollSec.coerceAtLeast(10)) * 1000L)
    } }

    private fun changed() = main.post { listeners.toList().forEach { it() } }
    private fun setStatus(s: String) { status = s; changed() }

    fun connect(address: String, name: String?) {
        if (!com.rlong.sleephub.core.BtPerms.canConnect(ctx)) { setStatus(com.rlong.sleephub.core.BtPerms.MSG); return }
        stop(false)
        prefs.edit().putString("zeo_mac", address).putString("zeo_name", name ?: "").putBoolean("zeo_enabled", true).apply()
        demo = false; state = State.CONNECTING; setStatus("Starting link to $address"); loadOffset(address)
        log.add("SYS", "start", null, "real headband $address ${name ?: ""}")
        link = RfcommZeoLink(ctx, address, log,
            onBytes = { b -> main.post { onBytes(b) } },
            onUp = { how -> main.post { onUp(how) } },
            onDown = { why -> main.post { onDown(why) } },
            onStatus = { s -> main.post { if (state != State.CONNECTED) setStatus(s) } })
    }
    fun connectDemo() {
        stop(false); demo = true; state = State.CONNECTING; setStatus("DEMO: starting simulated headband")
        log.add("SYS", "start", null, "DEMO simulated headband")
        link = ZeoSim({ b -> onBytes(b) }, { how -> onUp(how) })
    }
    fun tryAutoConnect() { if (link == null && com.rlong.sleephub.core.BtPerms.canConnect(ctx) && prefs.getBoolean("zeo_enabled", false)) savedAddress?.let { connect(it, savedName) } }
    /** User disconnect: stops link + auto-reconnect (keeps the saved device). */
    fun disconnect() { prefs.edit().putBoolean("zeo_enabled", false).apply(); stop(true) }
    fun forget() { disconnect(); prefs.edit().remove("zeo_mac").remove("zeo_name").apply(); info = null; hbState = null; sleep = null; changed() }
    private fun stop(announce: Boolean) {
        main.removeCallbacks(poll); link?.close(); link = null; asm.reset()
        state = State.DISCONNECTED; gotFirstState = false; tsState = 0
        if (announce) { log.add("SYS", "stop", null, "user"); setStatus("Disconnected") }
    }
    val active get() = link != null

    private fun onUp(how: String) {
        state = State.CONNECTED; gotFirstState = false; asm.reset()
        log.add("SYS", "up", null, how); setStatus("Connected ($how)")
        timeline.add(src, "link", "up", how)
        // Official flow: request device info (NULL), query state; SLEEP_SEND follows the first STATE_REPORT.
        send(Hmsg.requestStarting(), "request HB_STARTING")
        send(Hmsg.command(Hmsg.Cmd.QUERY_STATE), "query state")
        if (autoTimeSync) startTimeSync() else send(Hmsg.timeQuery(), "clock offset query (read-only)")
        main.removeCallbacks(poll); main.postDelayed(poll, pollSec.coerceAtLeast(10) * 1000L)
        com.rlong.sleephub.service.SleepHubService.start(ctx)
    }
    private fun onDown(why: String) {
        state = State.CONNECTING; log.add("SYS", "down", null, why); timeline.add(src, "link", "down", why)
        setStatus("$why — auto-reconnecting…")
    }

    fun send(frame: ByteArray, note: String = "") {
        val t = frame.getOrNull(7)?.toInt()?.and(0xFF) ?: -1
        log.add("TX", Hmsg.typeName(t), frame, note)
        link?.write(frame) ?: log.add("SYS", "writeFail", frame, "no link")
    }
    /** Probe screen entry point — enforces ZeoSafety on every frame. */
    fun sendProbe(frame: ByteArray, label: String): String? {
        val t = frame[7].toInt() and 0xFF; val body = frame.copyOfRange(Hmsg.HEADER, frame.size)
        ZeoSafety.check(t, body)?.let { log.add("SYS", "BLOCKED", frame, it); return it }
        send(frame, "PROBE $label"); return null
    }

    private fun onBytes(b: ByteArray) {
        lastRx = System.currentTimeMillis()
        log.add("RX", "raw", b)
        for (f in asm.feed(b)) {
            val m = ZeoParser.parse(f)
            log.add("RX", Hmsg.typeName(f.type), f.raw, "seq=${f.seq} ack=${f.ack} → ${describe(m)}")
            handle(m)
        }
    }

    private fun describe(m: ZeoMsg): String = when (m) {
        is ZeoMsg.Sleep -> "ZQ=${m.zq} base=${m.base.size} disp=${m.display.size} cur=${m.currentStage.label} deep=${m.timeInDeep} light=${m.timeInLight} rem=${m.timeInRem} wake=${m.timeInWake} valid=${m.valid}"
        is ZeoMsg.Unknown -> "UNKNOWN type ${m.type} body ${m.body.size}B"
        else -> m.toString().take(300)
    }

    private fun handle(m: ZeoMsg) {
        when (m) {
            is ZeoMsg.Starting -> {
                info = m
                timeline.add(src, "device", "${m.model} sn=${m.serial} sw=0x%08X hw=0x%08X".format(m.swVersion, m.hwVersion), "resetCause=${m.resetCause} query=${m.queryResponse}")
                if (!m.queryResponse && autoTimeSync) startTimeSync()   // headband rebooted
            }
            is ZeoMsg.State -> {
                val prev = hbState; hbState = m
                if (!gotFirstState) { gotFirstState = true; send(Hmsg.command(Hmsg.Cmd.SLEEP_SEND), "first state → sleep send") }
                if (prev == null || prev.onHead != m.onHead || prev.docked != m.docked || prev.algorithmMode != m.algorithmMode || prev.voltageStatus != m.voltageStatus || prev.voltage != m.voltage) {
                    timeline.add(src, "state", "onHead=${m.onHead} docked=${m.docked} algo=${m.algorithmLabel}", "")
                    timeline.add(src, "battery", m.voltage, "raw voltage; status=${m.voltageLabel}")
                }
            }
            is ZeoMsg.StateChange -> {
                lastEvent = m.label; timeline.add(src, "event", m.label, "code ${m.event}")
                if (m.event in listOf(8, 9, 10, 11, 12, 15)) { send(Hmsg.command(Hmsg.Cmd.QUERY_STATE), "event ${m.event}"); send(Hmsg.command(Hmsg.Cmd.SLEEP_SEND), "event ${m.event}") }
                if (m.event == 17 && autoTimeSync) startTimeSync()
            }
            is ZeoMsg.Sleep -> { sleep = m; emitEpochs(m); try { com.rlong.sleephub.App.I.zeoDb.put(m, clockOffsetMs, demo) } catch (e: Exception) { log.add("SYS", "zeoDb", null, "store failed: ${e.message}") } }
            is ZeoMsg.TimeReport -> onTimeReport(m)
            is ZeoMsg.Error -> timeline.add(src, "error", m.label)
            is ZeoMsg.WakeNotify -> timeline.add(src, "wakeup_notify", m.reason)
            is ZeoMsg.Accel -> timeline.add(src, "accel", m.diffVecMagSums.sum(), "sampleAvg=${m.sampleAverage}")
            is ZeoMsg.Unknown -> timeline.add(src, "unknown_type", m.type, "${m.body.size}B — see zeo_traffic.log")
            else -> Unit
        }
        changed()
    }

    /** Push newly seen 30-s base epochs into the timeline (times converted with clock offset if known). */
    private fun emitEpochs(m: ZeoMsg.Sleep) {
        if (m.startOfNight != emittedNight) { emittedNight = m.startOfNight; emittedEpochs = 0 }
        val off = clockOffsetMs ?: 0L
        val t0 = ZeoNights.wall(m.startOfNight, clockOffsetMs)
        for (i in emittedEpochs until m.base.size) {
            val st = m.base[i]
            timeline.add(src, "stage", st.label, "epoch $i" + if (clockOffsetMs == null) " (hb clock, offset unknown)" else "", time = if (m.startOfNight > 0) t0 + i * 30_000L else System.currentTimeMillis())
        }
        emittedEpochs = m.base.size
        timeline.add(src, "zq", m.zq, "deep=${m.timeInDeep} light=${m.timeInLight} rem=${m.timeInRem} wake=${m.timeInWake} awakenings=${m.awakenings}")
    }

    // ---- Time sync (Headband.TimeSync) ----
    fun startTimeSync() { tsState = 1; tsFixes = 0; tsSend = System.currentTimeMillis(); send(Hmsg.timeQuery(tsSend), "time sync query") }
    private fun onTimeReport(r: ZeoMsg.TimeReport) {
        val now = System.currentTimeMillis()
        if (!r.isOffset) {
            val o = now - r.timeMs; val first = clockOffsetMs == null || Math.abs(o - (clockOffsetMs ?: 0)) > 60_000L
            clockOffsetMs = o
            if (!demo) prefs.edit().putLong(offKey(savedAddress), o).apply()
            if (first) {
                log.add("SYS", "clockOffset", null, "phone−headband = $o ms (hb ${r.timeMs / 1000} s)")
                if (!demo) try { com.rlong.sleephub.App.I.zeoDb.remap(o); sleep?.let { com.rlong.sleephub.App.I.zeoDb.put(it, o, false) } } catch (_: Exception) {}
                emittedEpochs = 0; emittedNight = -1L; changed()
            }
        }
        if (tsState != 1 || r.isOffset) return
        val algo = hbState?.algorithmMode ?: -1
        val earliest = Hmsg.EARLIEST_SEC * 1000
        if (r.timeMs < earliest || (algo != 2 && algo != 3)) {
            if (Math.abs(r.timeMs - now) < 2000) { tsState = 0; log.add("SYS", "timesync", null, "in sync (Δ=${r.timeMs - now} ms)"); return }
            if (tsFixes >= 1) { tsState = 0; log.add("SYS", "timesync", null, "still off by ${r.timeMs - now} ms after correction — giving up"); return }
            tsFixes++
            val delta = tsSend - r.timeMs
            val body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                val a = Math.abs(delta); putInt((a / 1000).toInt()); putInt((a % 1000).toInt()); put(1); put(if (delta < 0) 1 else 0); put(0); put(0) }.array()
            send(Hmsg.build(Hmsg.T.TIME_REPORT, body), "time sync: set offset Δ=$delta ms")
            tsState = 1; tsSend = System.currentTimeMillis(); send(Hmsg.timeQuery(tsSend), "time sync re-query")
        } else { tsState = 0; log.add("SYS", "timesync", null, "skipped: algorithm active ($algo)") }
    }
}
