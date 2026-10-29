package com.rlong.sleephub.ring.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.os.Handler
import android.os.Looper
import com.rlong.sleephub.ring.data.RingDb
import com.rlong.sleephub.ring.data.TrafficLog
import com.rlong.sleephub.ring.proto.*
import com.rlong.sleephub.ring.sim.SimRing
import java.util.UUID

interface Transport {
    fun write(frame: ByteArray)
    fun read(uuid: UUID)
    fun close()
}

data class FoundRing(val address: String, val name: String, val rssi: Int, val info: String)

private fun prefs0(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

class Ring(private val ctx: Context, private val db: RingDb) {
    companion object {
        const val STREAM_IDLE_MS = 90_000L
        const val STREAM_RESTART_GAP_MS = 3_000L
        const val AUTO_SYNC_INTERVAL_MS = 45 * 60_000L
        /** Quiet refresh of today's 5-min tables while connected. */
        const val TODAY_PULL_MS = 15 * 60_000L
        /** Page 0 of each 5-min table for day 0 (collector follows the rest) + one-shot histories. */
        fun todayPullFrames(): List<ByteArray> = listOf(15, 16, 17, 22).map { Cmd.timingPage(it, 0, 0) } +
            listOf(Cmd.historyHeartRate(), Cmd.historyHrv(), Cmd.historySpO2(), Cmd.historyStress())
        /** Pure rule for the post-connect auto sync (at most once per hour). */
        /** A full sync that is still going after this long is ended (it otherwise blocks today-pulls and app auto SpO2). */
        const val SYNC_MAX_MS = 12 * 60_000L
        fun autoSyncDue(lastMs: Long, nowMs: Long) = lastMs <= 0L || nowMs - lastMs >= AUTO_SYNC_INTERVAL_MS || nowMs < lastMs
    }
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }
    var state = State.DISCONNECTED; private set
    var demo = false; private set
    /** SleepHub hook: every live value (metric, value, demo) goes to the combined timeline. */
    var onLive: ((Metric, Double, Boolean) -> Unit)? = null
    var deviceName = ""; var firmware = ""; var battery: BatteryReading? = null
    var status = "Not connected"; private set
    /** Active live measure, if any. */
    var measuring: Metric? = null; private set
    var measureDeadlineMs: Long = 0L; private set
    var measureMessage: String = ""; private set
    /** Sample source tag for the active measure ("live" = manual, "app-auto" = AutoSpo2 scheduler). */
    var measureSource: String = "live"; private set
    private var measureResult: Double? = null
    /** Called once when any measure ends: (metric, valid value or null, source). */
    var onMeasureFinished: ((Metric, Double?, String) -> Unit)? = null
    var pairedAddress: String? = null; private set
    var pairedName: String? = null; private set
    private var intentionalClose = false
    /** True after a user Disconnect/Forget: SleepHub's watchdog must not reconnect. */
    val userDisconnected get() = intentionalClose
    private var reconnectPosted = false
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
    private val measureTimeoutRunnable = Runnable { onMeasureTimeout() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (measuring != null) { changed(); main.postDelayed(this, 500) }
        }
    }

    val live = HashMap<Metric, Double>()
    val listeners = ArrayList<() -> Unit>()
    /** Probe screen: every complete FDD3 frame (and interesting char notifies). */
    val probeReplyListeners = ArrayList<(ByteArray) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var transport: Transport? = null
    private val parser = Parser()
    private val timing = TimingCollector(parser)
    private val actions = ActionCollector()
    /** session id (list index) -> session start, for anchoring 7/06 stages. */
    private val sessionStarts = HashMap<Int, Long>()
    /** payload -> day seen during the current full sync; guards against a ring echoing one day's data for another index. */
    private val seenTiming = HashMap<String, Int>()
    /** Latest ring interval settings (cmd -> 5-minute steps) and raw 2/15 temp state, cached across launches. */
    val intervals = HashMap<Int, Int>().apply { for (c in listOf(6, 7, 8, 45)) prefs0(ctx).getInt("interval_$c", -1).takeIf { it >= 0 }?.let { put(c, it) } }
    var tempState: ByteArray? = prefs0(ctx).getString("temp_state", null)?.let { runCatching { hexBytes(it) }.getOrNull() }; private set
    private var syncVitalDays = 0
    private fun triPref(k: String): Boolean? = prefs0(ctx).getInt(k, -1).let { if (it < 0) null else it == 1 }
    var gomoreSupported: Boolean? = triPref("gomore_sup"); private set
    var gomoreKey: Boolean? = triPref("gomore_key"); private set
    /** Did the last full sync get any 7/0E page back? */
    var energyAnswered: Boolean? = triPref("energy_answered"); private set
    private var syncEnergyPages = 0
    var spo2Type: Int? = prefs0(ctx).getInt("spo2_type", -1).takeIf { it >= 0 }; private set
    var autoMeasureMsg = ""; private set
    private val pendingWrite = HashMap<Metric, Int>()
    /** Read-only: current intervals + SpO2 type. */
    fun queryAutoMeasure() { AutoMeasure.METRICS.forEach { send(AutoMeasure.query(it)) }; send(AutoMeasure.querySpO2Type()) }
    /** Ring-setting write (Settings → Ring auto-measure only): 1/06, 1/07, 1/08, 1/27 with the SDK encoding, then read back. */
    fun writeAutoMeasure(m: Metric, minutes: Int): Boolean {
        if (state != State.CONNECTED) return false
        val steps = AutoMeasure.steps(minutes)
        pendingWrite[m] = steps; autoMeasureMsg = "${m.label}: writing ${AutoMeasure.label(steps)}…"
        send(AutoMeasure.write(m, minutes), "auto-measure write ${m.label}=${minutes}min")
        main.postDelayed({ send(AutoMeasure.query(m), "read-back") }, 600)
        changed(); return true
    }
    private val seenSleep = HashMap<String, Int>(); private val seenAction = HashMap<String, Int>()
    var syncSummary: String = prefs0(ctx).getString("full_sync_summary", "") ?: ""; private set
    private var syncNights = 0; private var syncMoveDays = 0; private var syncSessions = 0
    private val asm = Frame.Assembler()
    private var busy = false
    private var scanCb: ScanCallback? = null

    private fun changed() = main.post { listeners.toList().forEach { it() } }
    private fun status(s: String) { status = s; changed() }

    @SuppressLint("MissingPermission")
    fun scan(onFound: (FoundRing) -> Unit): String? {
        if (!com.rlong.sleephub.core.BtPerms.canScan(ctx) || !com.rlong.sleephub.core.BtPerms.canConnect(ctx)) { status(com.rlong.sleephub.core.BtPerms.MSG); return com.rlong.sleephub.core.BtPerms.MSG }
        return try { scanChecked(onFound) } catch (e: SecurityException) { status(com.rlong.sleephub.core.BtPerms.MSG); com.rlong.sleephub.core.BtPerms.MSG }
    }
    private val scanTimeout = Runnable { stopScan(); if (status == "Scanning…") status("Scan finished") }
    @SuppressLint("MissingPermission")
    private fun scanChecked(onFound: (FoundRing) -> Unit): String? {
        val bm = ctx.getSystemService(BluetoothManager::class.java)
        val ad = bm?.adapter ?: return "No Bluetooth adapter"
        if (!ad.isEnabled) return "Bluetooth is off"
        val sc = ad.bluetoothLeScanner ?: return "No LE scanner"
        scanCb?.let { try { sc.stopScan(it) } catch (_: Exception) {} }
        main.removeCallbacks(scanTimeout)
        // A ring that is bonded or already GATT-connected to Android (e.g. by Da Rings) may stop advertising: list those too (rssi -999).
        fun ringLike(n: String?) = n != null && n != "ble_dfu" && (n.contains("ring", true) || n.startsWith("R", false) && n.length <= 12)
        try {
            val seen = HashSet<String>()
            (bm.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT).map { it to "connected" } + ad.bondedDevices.map { it to "paired" }).forEach { (d, how) ->
                val n = try { d.name } catch (_: SecurityException) { null }
                if (d.type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC && ringLike(n) && seen.add(d.address)) onFound(FoundRing(d.address, n ?: "(no name)", -999, how))
            }
        } catch (_: Exception) {}
        val cb = object : ScanCallback() {
            override fun onScanResult(cbType: Int, r: ScanResult) {
                val d = r.device; val info = parseScan(r.scanRecord?.bytes)
                // Some rings advertise the name only in the scan response; keep nameless devices that carry MoYoung FDDA/D0FD service data.
                val name = (try { d.name } catch (_: SecurityException) { null }) ?: r.scanRecord?.deviceName ?: if (info.isNotEmpty()) "(no name)" else return
                if (name.isBlank() || name == "ble_dfu") return
                if (name.contains("Ring", ignoreCase = true) || info.isNotEmpty()) {
                    TrafficLog.add("RX", "ADV ${d.address}", r.scanRecord?.bytes, "$name $info")
                }
                onFound(FoundRing(d.address, name, r.rssi, info))
            }
            override fun onScanFailed(errorCode: Int) { status("Scan failed $errorCode") }
        }
        scanCb = cb
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed(scanTimeout, 30_000)
        status("Scanning…"); return null
    }
    @SuppressLint("MissingPermission")
    fun stopScan() { main.removeCallbacks(scanTimeout); scanCb?.let { try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {} }; scanCb = null }

    private fun parseScan(raw: ByteArray?): String {
        if (raw == null) return ""
        // CRPScanRecordParser: look for AD type 0x16 (service data) whose payload starts with DA FD (device) or D0 FD (setting)
        var i = 0
        while (i + 2 < raw.size) {
            val len = raw[i].toInt() and 0xFF; if (len == 0) break
            val type = raw[i + 1].toInt() and 0xFF
            if (type == 0x16 && len >= 3) {
                val p = raw.copyOfRange(i + 2, minOf(raw.size, i + 1 + len))
                if (p.size >= 8 && p[0] == 0xDA.toByte() && p[1] == 0xFD.toByte()) {
                    val fw = String(p, 2, 3)
                    return "fw=$fw plat=${p[5].toInt() and 0xFF} chip=${p[6].toInt() and 0xFF}"
                }
                if (p.size >= 6 && p[0] == 0xD0.toByte() && p[1] == 0xFD.toByte()) {
                    return "color=${p[2].toInt() and 0xFF} size=${p[3].toInt() and 0xFF} type=${p[4].toInt()} charging=${p[5] > 0}"
                }
            }
            i += len + 1
        }
        return ""
    }

    fun connect(address: String, name: String? = null, autoConnect: Boolean = false) {
        if (!com.rlong.sleephub.core.BtPerms.canConnect(ctx)) { status(com.rlong.sleephub.core.BtPerms.MSG); changed(); return }
        try { connectChecked(address, name, autoConnect) } catch (e: SecurityException) { state = State.DISCONNECTED; status(com.rlong.sleephub.core.BtPerms.MSG); changed() }
    }
    private fun connectChecked(address: String, name: String?, autoConnect: Boolean) {
        stopScan(); intentionalClose = false
        disconnect(clearTransportOnly = true)
        demo = false; state = State.CONNECTING
        status(if (autoConnect) "Auto-connecting to $address…" else "Connecting to $address…"); changed()
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val device = ad?.getRemoteDevice(address)
        if (device == null) { status("Unknown device"); state = State.DISCONNECTED; return }
        if (!name.isNullOrBlank()) pairedName = name
        pairedAddress = address
        prefs.edit().putString("paired_mac", address).putString("paired_name", pairedName ?: "").apply()
        transport = GattTransport(ctx, device, ::onNotify, ::onReady, ::onLost, autoConnect) { st ->
            (transport as? GattTransport)?.noResponse = preferNoResponse
            finishedWrite(if (st == BluetoothGatt.GATT_SUCCESS) "ok" else "status=$st")
        }.also { it.noResponse = preferNoResponse; it.onWriteGaveUp = ::onWriteGaveUp }
    }
    fun connectDemo() {
        stopScan(); intentionalClose = true
        disconnect(clearTransportOnly = true)
        demo = true; state = State.CONNECTING; status("Connecting to simulated ring…"); changed()
        transport = SimRing(::onNotify, ::onReady, ::onLost) { finishedWrite("ok") }
    }
    /** User tapped Disconnect — do not auto-reconnect until they connect again (MAC stays saved). */
    fun disconnect() {
        intentionalClose = true
        cancelMeasure("cancelled")
        disconnect(clearTransportOnly = false)
        status("Disconnected"); changed()
    }
    private fun disconnect(clearTransportOnly: Boolean) {
        main.removeCallbacks(measureTimeoutRunnable); main.removeCallbacks(tickRunnable)
        main.post { if (clearTransportOnly && fullSyncRunning && !demo) cmdQueue.suspend() else cmdQueue.clear() }; busy = false; asm.reset()
        transport?.close(); transport = null
        if (!clearTransportOnly) state = State.DISCONNECTED
    }
    fun forgetRing() {
        intentionalClose = true
        cancelMeasure("forgotten")
        disconnect(clearTransportOnly = false)
        pairedAddress = null; pairedName = null; deviceName = ""; firmware = ""; battery = null
        prefs.edit().remove("paired_mac").remove("paired_name").apply()
        try { KeepAliveService.stop(ctx) } catch (_: Exception) {}
        status("Ring forgotten"); changed()
    }
    fun tryAutoConnect() {
        if (demo || state != State.DISCONNECTED) return
        if (!com.rlong.sleephub.core.BtPerms.canConnect(ctx)) return
        val mac = prefs.getString("paired_mac", null) ?: return
        pairedAddress = mac
        pairedName = prefs.getString("paired_name", null)
        connect(mac, pairedName, autoConnect = true)
    }
    fun stayConnectedBg(): Boolean = prefs.getBoolean("stay_connected_bg", false)
    fun setStayConnectedBg(on: Boolean) {
        prefs.edit().putBoolean("stay_connected_bg", on).apply()
        if (on || com.rlong.sleephub.ring.auto.AutoSpo2.enabled(ctx)) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
    }

    private fun onReady() {
        reconnectPosted = false
        state = State.CONNECTED
        status("Connected" + (pairedName?.let { " ($it)" } ?: ""))
        changed()
        main.post { if (cmdQueue.size > 0) TrafficLog.add("SYS", "resume", null, "${cmdQueue.size} queued commands after reconnect"); cmdQueue.resume() }
        if (fullSyncRunning) (transport as? GattTransport)?.priority(true)
        send(Cmd.syncTime()); send(Cmd.realtimeBattery()); send(Cmd.firmwareVersion()); send(Cmd.deviceInfo())
        transport?.read(Uuids.BATTERY)
        transport?.read(Uuids.STEPS)
        if (stayConnectedBg() || com.rlong.sleephub.ring.auto.AutoSpo2.enabled(ctx)) KeepAliveService.start(ctx)
        if (streamSource != null) main.postDelayed({ if (streamSource != null && state == State.CONNECTED) { streamRestarts++; TrafficLog.add("SYS", "stream", null, "reconnected — restart #$streamRestarts"); pauseQueueForMeasure(); direct(Cmd.measureSpO2(true), "stream restart") } }, 2500)
        main.removeCallbacks(todayPull); main.postDelayed(todayPull, TODAY_PULL_MS)
        maybeAutoSync("reconnect")
    }
    private fun onLost(why: String) {
        // keep the queue: a mid-sync drop resumes where it stopped after the auto-reconnect
        main.removeCallbacks(todayPull); main.post { cmdQueue.suspend() }
        state = State.DISCONNECTED; status(why); cancelMeasure("measurement failed: ring disconnected"); pausedForMeasure = false; changed()
        if (!intentionalClose && !demo && pairedAddress != null && !reconnectPosted) {
            reconnectPosted = true
            status("$why — reconnecting…"); changed()
            main.postDelayed({
                reconnectPosted = false
                if (state == State.DISCONNECTED && !intentionalClose) tryAutoConnect()
            }, 2000)
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        when {
            uuid.toString().contains("fdd3") -> {
                TrafficLog.add("RX", "FDD3", value)
                asm.feed(value)?.let { frame ->
                    TrafficLog.add("RX", describeFrame(frame), frame, "reply")
                    probeReplyListeners.toList().forEach { it(frame) }
                    main.post {
                        // a reply to an already-completed command (retry echo) must not restart paging
                        if (cmdQueue.onReply(frame) == ReplyKind.DUPLICATE) TrafficLog.add("SYS", "dupReply", null, describeFrame(frame) + " ignored")
                        else handle(parser.frame(frame))
                    }
                    // do not gate on FDD3 — measure replies are async; write completion is the gate
                }
            }
            uuid.toString().contains("fdd1") -> {
                val steps = parser.currentSteps(value)
                val note = if (steps is RingEvent.CurrentSteps) "steps=${steps.steps} dist=${steps.distance} kcal=${steps.calories}" else "unparsed len=${value.size}"
                TrafficLog.add("RX", "FDD1", value, note)
                handle(steps)
            }
            uuid.toString().contains("2a37") -> {
                TrafficLog.add("RX", "2A37", value)
                handle(parser.heartRateChar(value))
            }
            uuid.toString().contains("2a19") -> {
                if (value.isNotEmpty()) {
                    val raw = value[0].toInt() and 0xFF
                    val br = BatteryReading.fromRaw(raw)
                    battery = br
                    TrafficLog.add("RX", "2A19", value, "raw=$raw → ${br.label}")
                    changed()
                }
            }
            else -> TrafficLog.add("RX", uuid.toString().takeLast(8), value)
        }
    }

    private fun handle(e: RingEvent?) {
        if (e == null) return
        when (e) {
            is RingEvent.Live -> {
                live[e.metric] = e.value
                onLive?.invoke(e.metric, e.value, demo)
                if (streamSource != null && e.metric == Metric.SPO2 && measuring == null) { onStreamValue(e.value); changed(); return }
                val auto = measuring == e.metric && measureSource == com.rlong.sleephub.ring.auto.AutoSpo2Logic.SOURCE
                if (measuring == e.metric) measureResult = e.value
                if (!auto) db.addSamples(listOf(Sample(e.metric, System.currentTimeMillis(), e.value, "live")), demo)
                else if (com.rlong.sleephub.ring.auto.AutoSpo2Logic.valid(e.value)) db.addSamples(listOf(Sample(e.metric, System.currentTimeMillis(), e.value, measureSource)), demo)
                else TrafficLog.add("SYS", "autoSpO2", null, "discard invalid ${e.value}")
                if (measuring == e.metric) finishMeasure("got ${e.metric.label}=${if (e.metric == Metric.TEMP) "%.1f".format(e.value) else e.value.toInt()}")
                changed()
            }
            is RingEvent.MeasureFailed -> {
                if (streamSource != null && e.metric == Metric.SPO2 && measuring == null) { streamFails++; TrafficLog.add("SYS", "stream", null, "fail/off-finger (0xFF) #$streamFails"); restartStreamSoon(3_000); return }
                measureMessage = e.reason
                if (measuring == e.metric) finishMeasure(e.reason, failed = true)
                changed()
            }
            is RingEvent.History -> { db.addSamples(e.samples, demo); changed() }
            is RingEvent.TimingPage -> {
                if (e.cmd == Cmd.ENERGY_TABLE) syncEnergyPages++
                val (next, done) = timing.onPage(e)
                if (next != null) send(next, front = true)
                if (fullSyncRunning) finishSyncSoon()
                if (done != null && done.isNotEmpty()) {
                    // duplicate-day guard: identical non-empty table for a different day index → skip
                    val key = "${e.cmd}:" + done.joinToString(",") { "%.1f@%d".format(java.util.Locale.US, it.value, (it.time - parser.dayStart(e.day)) / 300_000L) }
                    val dup = seenTiming[key]
                    if (dup != null && dup != e.day) TrafficLog.add("SYS", "timingDup", null, "cmd ${e.cmd} day ${e.day} identical to day $dup — skipped")
                    else { seenTiming[key] = e.day; db.addSamples(done, demo); syncVitalDays += 1 }
                    changed()
                }
            }
            is RingEvent.Sleep -> {
                val key = e.raw.copyOfRange(1, e.raw.size).hex()
                val dup = seenSleep[key]
                if (e.segments.isNotEmpty() && dup != null && dup != e.day) TrafficLog.add("SYS", "sleepDup", null, "day ${e.day} identical to day $dup — skipped")
                else { if (e.segments.isNotEmpty()) seenSleep[key] = e.day
                    if (db.putSleep(parser.dayStart(e.day), e.segments, e.raw, demo)) syncNights++ }
                if (e.day == 0 && e.segments.isNotEmpty()) e.segments.maxByOrNull { it.end }!!.let { s ->
                    if (s.end != lastStageEnd) TrafficLog.add("SYS", "hypnogram", null, "day0 last stage ${s.stage} ends ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(s.end))} (lag ${(System.currentTimeMillis() - s.end) / 60000} min)")
                    lastStageEnd = s.end; lastStage = s.stage }
                if (fullSyncRunning) finishSyncSoon(); changed()
            }
            is RingEvent.ActionPage -> {
                val (next, done) = actions.onPage(e)
                if (next != null) send(next, front = true)
                if (fullSyncRunning) finishSyncSoon()
                if (done != null && done.isNotEmpty()) {
                    val key = done.joinToString(",")
                    val dup = seenAction[key]
                    if (done.any { it != 0 } && dup != null && dup != e.day) TrafficLog.add("SYS", "actionDup", null, "day ${e.day} identical to day $dup — skipped")
                    else { seenAction[key] = e.day
                        val limit = if (e.day == 0) System.currentTimeMillis() else Long.MAX_VALUE
                        if (db.putMovementDay(parser.dayStart(e.day), done, limit, demo) > 0) syncMoveDays++ }
                    changed()
                }
            }
            is RingEvent.SleepList -> {
                sessionStarts.clear()
                e.starts.forEachIndexed { i, t -> sessionStarts[i] = t; send(Cmd.sleepDetails(i)); send(Cmd.sleepInfo(i)) }
                send(Cmd.realtimeBattery(), "end of sleep sync")
                TrafficLog.add("SYS", "sleepList", null, "${e.starts.size} sessions")
            }
            is RingEvent.SleepDetails -> {
                if (e.session.end > e.session.start && e.session.start > 86400_000L) { sessionStarts[e.session.id] = e.session.start; db.putSession(e.session, demo); syncSessions++ }
                finishSyncSoon(); changed()
            }
            is RingEvent.SessionStages -> {
                sessionStarts[e.id]?.let { start -> db.putSessionStages(start, parser.stagesAt(e.raw, start), demo) }
                finishSyncSoon(); changed()
            }
            is RingEvent.StepsDay -> { db.putStepsDay(parser.dayStart(e.day), e.steps, e.distance, e.calories, demo); changed() }
            is RingEvent.StepsDetails -> { db.putSteps30(parser.dayStart(e.day), e.perHalfHour, demo); changed() }
            is RingEvent.CurrentSteps -> { live[Metric.STEPS] = e.steps.toDouble(); onLive?.invoke(Metric.STEPS, e.steps.toDouble(), demo); changed() }
            is RingEvent.Battery -> { battery = BatteryReading.fromRaw(e.percent); changed() }
            is RingEvent.Firmware -> { firmware = e.version; changed() }
            is RingEvent.DeviceInfo -> {
                deviceName = e.name; if (firmware.isEmpty()) firmware = e.version
                if (e.name.isNotBlank()) { pairedName = e.name; prefs.edit().putString("paired_name", e.name).apply() }
                changed()
            }
            is RingEvent.GomoreSupport -> { gomoreSupported = e.supported; prefs.edit().putInt("gomore_sup", if (e.supported) 1 else 0).apply(); TrafficLog.add("SYS", "gomore", null, "supported=${e.supported} ver=${e.version}"); changed() }
            is RingEvent.GomoreKey -> { gomoreKey = e.saved; prefs.edit().putInt("gomore_key", if (e.saved) 1 else 0).apply(); TrafficLog.add("SYS", "gomoreKey", null, "saved=${e.saved}"); changed() }
            is RingEvent.SpO2Type -> { spo2Type = e.type; prefs.edit().putInt("spo2_type", e.type).apply(); TrafficLog.add("SYS", "spo2Type", null, AutoMeasure.spo2TypeLabel(e.type)); changed() }
            is RingEvent.TimingInterval -> {
                AutoMeasure.metricForQuery(e.cmd)?.let { m -> pendingWrite.remove(m)?.let { want ->
                    autoMeasureMsg = if (want == e.steps) "${m.label}: confirmed ${AutoMeasure.label(e.steps)} (ring replied ${"%02X".format(e.steps)})"
                        else "${m.label}: wrote ${AutoMeasure.label(want)} but ring reports ${AutoMeasure.label(e.steps)} (reply ${"%02X".format(e.steps)})"
                    TrafficLog.add("SYS", "autoMeasure", null, autoMeasureMsg) } }
                intervals[e.cmd] = e.steps; prefs.edit().putInt("interval_${e.cmd}", e.steps).apply()
                TrafficLog.add("SYS", "interval", null, "${Intervals.NAMES[e.cmd]} steps=${e.steps} → ${Intervals.minutes(e.steps)} min"); changed() }
            is RingEvent.TimingState -> if (e.cmd == 21 && e.raw.isNotEmpty()) { tempState = e.raw; prefs.edit().putString("temp_state", e.raw.hex()).apply(); changed() }
            is RingEvent.SleepState -> { ringSleepState = e.state; ringSleepStateAt = System.currentTimeMillis(); TrafficLog.add("SYS", "sleepState", null, "ring says state=${e.state}"); changed() }
            is RingEvent.Unknown -> Unit
        }
    }

    /** Preferred write type for FDD2. Real log: every WRITE_TYPE_DEFAULT → status=3 WRITE_NOT_PERMITTED; stock SDK uses NO_RESPONSE for V1. */
    var preferNoResponse = true
    private val sched = object : Scheduler {
        override fun post(delayMs: Long, r: Runnable) { main.postDelayed(r, delayMs) }
        override fun cancel(r: Runnable) { main.removeCallbacks(r) }
    }
    /** ONE queue for every ring command (sync, 15-min refresh, settings, probe): window 4, 20 s timeout, 1 retry, 80 ms gap. */
    val cmdQueue = CommandQueue(sched, { f, note ->
        TrafficLog.add("TX", describeFrame(f), f, note.ifEmpty { "noResp=$preferNoResponse" })
        (transport as? GattTransport)?.noResponse = preferNoResponse
        transport?.write(f)
    }, { msg -> TrafficLog.add("SYS", msg, null) }, timeoutMs = 8_000).also { q ->
        q.enabled = false
        q.onProgress = { if (fullSyncRunning) { finishSyncSoon(25_000); status("Syncing… ${q.size} left (sent ${q.sent}, ${q.timeouts} timeouts)") } }
        q.onIdle = { if (fullSyncRunning) finishSyncSoon(1500) }
        q.onTimeout = { item -> val e = item.expect!!
            // a feature counts as unsupported only after both tries timed out
            when {
                e.group == 7 && e.cmd == 0 -> { gomoreSupported = false; prefs.edit().putInt("gomore_sup", 0).apply(); TrafficLog.add("SYS", "unsupported", null, "7/00 Gomore: no reply to 2 tries") }
                e.group == 7 && (e.cmd == 12 || e.cmd == 14) && syncEnergyPages == 0 -> { energyAnswered = false; prefs.edit().putInt("energy_answered", 0).apply(); TrafficLog.add("SYS", "unsupported", null, "${e.label()} energy: no reply to 2 tries") }
                e.group == 2 && e.cmd in listOf(0x25, 0x2D, 0x2F) -> TrafficLog.add("SYS", "unsupported", null, "${e.label()}: no reply to 2 tries")
            }
        }
    }
    /** Thread-safe entry point; [front] puts follow-up pages ahead of the rest of the sync. */
    fun send(frame: ByteArray, note: String = "", front: Boolean = false, serial: Boolean = false) {
        main.post { if (state == State.CONNECTED || demo || fullSyncRunning) cmdQueue.enqueue(frame, note, front, serial) }
    }
    /** GATT write completions are only logged now; the queue gates on replies/timeouts. */
    fun finishedWrite(why: String = "ok") { if (why != "ok") TrafficLog.add("SYS", "writeGate", null, why) }

    /** Pull the usual history the Da Rings app asks for after connect (0=today … 6=6 days ago). */
    fun syncHistory(days: Int = 7) {
        if (streamSource != null) { TrafficLog.add("SYS", "syncSkip", null, "7-day sync: continuous SpO2 running"); return }
        if (fullSyncRunning) { TrafficLog.add("SYS", "syncCoalesced", null, "full sync already running — 7-day sync ignored"); return }
        status("Syncing history…")
        for (d in 0 until days) {
            send(Cmd.historySteps(d)); send(Cmd.historyStepsDetails(d)); send(Cmd.historySleep(d))
            send(Cmd.timingHeartRatePage(d, 0)); send(Cmd.timingSpO2Page(d, 0))
            send(Cmd.timingHrvPage(d, 0)); send(Cmd.timingStressPage(d, 0)); send(Cmd.timingTempPage(d, 0))
        }
        send(Cmd.historyHeartRate()); send(Cmd.historySpO2()); send(Cmd.historyHrv()); send(Cmd.historyStress())
        send(Cmd.realtimeBattery()); status("Connected")
    }
    var lastTodayPull = 0L; private set
    private val todayPull: Runnable = object : Runnable { override fun run() {
        if (state != State.CONNECTED) return
        // v15: the periodic pull is the incremental sync (only pages since the last sync), foreground only
        val fg = (ctx.applicationContext as? com.rlong.sleephub.App)?.foreground == true
        if (fg && !fullSyncRunning && measuring == null) { lastTodayPull = System.currentTimeMillis(); syncAllHistory(auto = true, trigger = "today pull") }
        main.postDelayed(this, TODAY_PULL_MS)
    } }
    var fullSyncRunning = false; private set
    private var syncStartedAt = 0L; private var syncTimeouts0 = 0
    private val finishSync = Runnable {
        if (!fullSyncRunning) return@Runnable
        if (System.currentTimeMillis() - syncStartedAt > SYNC_MAX_MS) {
            TrafficLog.add("SYS", "syncWatchdog", null, "full sync running ${(System.currentTimeMillis() - syncStartedAt) / 60000} min — finishing, dropping ${cmdQueue.size} queued")
            main.post { cmdQueue.clear() }
        } else if (cmdQueue.size > 0) { finishSyncSoon(25_000); return@Runnable }   // still working (or waiting for reconnect)
        fullSyncRunning = false
        (transport as? GattTransport)?.priority(false)
        val tos = cmdQueue.timeouts - syncTimeouts0
        TrafficLog.add("SYS", "syncDone", null, "took ${(System.currentTimeMillis() - syncStartedAt) / 1000.0}s, ${cmdQueue.sent - syncCmds0} sends, $tos timeouts")
        if (tos == 0 && state == State.CONNECTED) {
            val done = (prefs.getStringSet("complete_days", emptySet())!! + syncDays.filter { it >= 1 }.map { parser.dayStart(it).toString() })
                .filter { it.toLong() >= parser.dayStart(SyncPlan.MAX_DAY) }.toSet()
            prefs.edit().putStringSet("complete_days", done).putLong("last_sync_ok", syncStartedAt).apply()
        }
        if (syncEnergyPages > 0) { energyAnswered = true; prefs.edit().putInt("energy_answered", 1).apply() }
        syncSummary = "Last full sync ${java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(java.util.Date())}: " +
            "$syncNights nights, $syncMoveDays movement days, $syncSessions sessions, $syncVitalDays vitals tables (days ${syncDays.joinToString(",")})"
        prefs.edit().putString("full_sync_summary", syncSummary).apply()
        status(if (state == State.CONNECTED) "Connected" else status); changed()
    }
    private fun finishSyncSoon(ms: Long = 4000) { main.removeCallbacks(finishSync); main.postDelayed(finishSync, ms) }

    /**
     * Download every day the ring can hold: CRPHistoryDay TODAY(0)…DAYS_AGO_14 for 2/0E stages and 2/0x13 movement
     * (8 pages each, auto-followed), then the 7/04 session list with 7/05 details + 7/06 stages per session.
     */
    /** Automatic full sync: at most once per AUTO_SYNC_INTERVAL_MS and only while the app is on screen (background syncs crawl at 500 ms intervals and the link drops). */
    fun maybeAutoSync(trigger: String) {
        val fg = (ctx.applicationContext as? com.rlong.sleephub.App)?.foreground == true
        if (!autoSyncDue(prefs.getLong("last_full_sync", 0L), System.currentTimeMillis())) return
        if (!fg) { TrafficLog.add("SYS", "syncSkip", null, "$trigger: app in background — full sync deferred until the app is opened"); return }
        main.postDelayed({ if (state == State.CONNECTED) syncAllHistory(auto = true, trigger = trigger) }, 1500)
    }
    fun syncAllHistory(auto: Boolean = false, trigger: String = "ui") {
        if (state != State.CONNECTED) return
        if (!fullSyncRunning) TrafficLog.add("SYS", "syncStart", null, "trigger=$trigger auto=$auto")
        if (streamSource != null) { TrafficLog.add("SYS", "syncSkip", null, "$trigger: continuous SpO2 running"); return }
        if (fullSyncRunning) { TrafficLog.add("SYS", "syncCoalesced", null, "full sync already running (${cmdQueue.size} queued) — request ignored"); return }
        fullSyncRunning = true; syncNights = 0; syncMoveDays = 0; syncSessions = 0
        seenSleep.clear(); seenAction.clear(); seenTiming.clear(); syncVitalDays = 0; syncEnergyPages = 0
        prefs.edit().putLong("last_full_sync", System.currentTimeMillis()).apply()
        status(if (auto) "Auto-syncing sleep history…" else "Syncing all sleep history…")
        syncStartedAt = System.currentTimeMillis(); syncTimeouts0 = cmdQueue.timeouts
        (transport as? GattTransport)?.priority(true)
        val plan = SyncPlan.build(full = !auto && trigger != "incremental" && fullRequested, nowMs = System.currentTimeMillis(),
            lastSyncMs = prefs.getLong("last_sync_ok", 0L), yesterdayDone = prefs.getStringSet("complete_days", emptySet())!!.contains(parser.dayStart(1).toString()),
            needIntervals = intervals.isEmpty(), dayStart0 = parser.dayStart(0))
        fullRequested = false
        syncDays = plan.days; syncCmds0 = cmdQueue.sent
        TrafficLog.add("SYS", "syncPlan", null, plan.describe())
        plan.timingStart.forEach { (cmd, page) -> timing.startAt(cmd, 0, page) }
        if (plan.actionStart > 0) actions.startAt(0, plan.actionStart)
        plan.frames.forEach { send(it) }
        finishSyncSoon(20_000)
    }
    /** Days fetched by the running sync (to mark complete) and queue counter at start. */
    private var syncDays: List<Int> = emptyList(); private var syncCmds0 = 0
    private var fullRequested = false
    /** Deliberate 7-day refetch (History → Sync all history, after confirmation). */
    fun syncFullWeek() { fullRequested = true; syncAllHistory(trigger = "full 7-day refetch") }

    fun measure(m: Metric, on: Boolean) {
        if (on) startMeasure(m) else cancelMeasure("user stop")
    }
    fun startMeasure(m: Metric, source: String = "live") {
        if (state != State.CONNECTED) { measureMessage = "Not connected"; changed(); onMeasureFinished?.invoke(m, null, source); return }
        if (streamSource != null) stopStream("pre-empted by ${m.label} measure ($source)")
        measureSource = source; measureResult = null
        pauseQueueForMeasure()
        (transport as? GattTransport)?.priority(true)   // short connection interval: fewer supervision-timeout drops with the screen off
        // stop any other active measure first
        measuring?.let { if (it != m) sendStop(it) }
        measuring = m
        measureDeadlineMs = System.currentTimeMillis() + measureTimeoutMs(m)
        measureMessage = "Measuring ${m.label}… keep finger still"
        measureStartAt = System.currentTimeMillis()
        sendStart(m)
        main.removeCallbacks(measureTimeoutRunnable)
        main.postDelayed(measureTimeoutRunnable, measureTimeoutMs(m))
        main.removeCallbacks(tickRunnable); main.post(tickRunnable)
        changed()
    }
    fun cancelMeasure(why: String = "cancelled") {
        val m = measuring ?: return
        sendStop(m)
        finishMeasure(why, failed = why.contains("fail") || why.contains("timeout"), sendStopAlready = true)
    }
    var measureStartAt = 0L; private set
    /** The start frame never reached the ring: end the measurement now (no stale 'Measuring'), so callers can retry. */
    internal fun onWriteGaveUp(f: ByteArray) {
        val m = measuring ?: return
        if (f.size >= 7 && f[6] == 1.toByte() && f[4] == 1.toByte()) finishMeasure("Waiting for ring — start command not delivered (BLE busy)", failed = true, sendStopAlready = true)
    }
    private fun onMeasureTimeout() {
        val m = measuring ?: return
        TrafficLog.add("SYS", "measureTimeout", null, m.label)
        sendStop(m)
        finishMeasure("${m.label} failed: no result in ${measureTimeoutMs(m) / 1000}s — wear the ring snugly, keep still and retry", failed = true, sendStopAlready = true)
    }
    private fun finishMeasure(msg: String, failed: Boolean = false, sendStopAlready: Boolean = false) {
        val m = measuring
        measuring = null
        measureDeadlineMs = 0
        val src = measureSource; val res = if (failed) null else measureResult
        measureSource = "live"; measureResult = null
        resumeQueueAfterMeasure()
        if (m != null) onMeasureFinished?.let { cb -> main.post { cb(m, res, src) } }
        measureMessage = msg
        main.removeCallbacks(measureTimeoutRunnable)
        main.removeCallbacks(tickRunnable)
        if (!sendStopAlready && m != null && !failed) {
            // Result arrived — tell ring to stop (original app stops after onOnceMeasureComplete)
            sendStop(m)
        }
        changed()
    }
    // ---- sleep signals (cheap polls) ----
    var ringSleepState: Int? = null; private set
    var ringSleepStateAt = 0L; private set
    var lastStageEnd = 0L; private set
    var lastStage: Int? = null; private set
    /** 2/30 + 7/0A sleep state and today's hypnogram: three single-frame reads. */
    fun pollSleep() { if (state != State.CONNECTED || fullSyncRunning || measuring != null || streamSource != null) return
        // 2/30 never answers on this ring (only queue retries that clog GATT) — not sent
        send(Frame.build(7, 0x0A), "sleep poll"); send(Cmd.historySleep(0), "sleep poll") }

    // ---- continuous SpO2 stream: start without stop; every value is stored; restarted if the ring stops ----
    var streamSource: String? = null; private set
    var streamStartedAt = 0L; private set
    var streamCount = 0; private set
    var streamLastAt = 0L; private set
    private var streamFails = 0
    private var streamRestarts = 0
    private val streamGaps = ArrayList<Long>()
    val streaming get() = streamSource != null
    private val streamWatch = object : Runnable { override fun run() {
        if (streamSource == null) return
        val idle = System.currentTimeMillis() - maxOf(streamLastAt, streamStartedAt, streamCycleStart)
        if (state == State.CONNECTED && idle > STREAM_IDLE_MS) { streamRestarts++; streamCycleStart = System.currentTimeMillis(); TrafficLog.add("SYS", "stream", null, "no value for ${idle / 1000}s — restart #$streamRestarts"); pauseQueueForMeasure(); direct(Cmd.measureSpO2(true), "stream restart") }
        main.postDelayed(this, 15_000)
    } }
    fun startStream(source: String) {
        if (state != State.CONNECTED || measuring != null) return
        streamSource = source; streamStartedAt = System.currentTimeMillis(); streamCount = 0; streamLastAt = 0; streamFails = 0; streamRestarts = 0; streamGaps.clear()
        TrafficLog.add("SYS", "stream", null, "start ($source) — no stop will be sent")
        pauseQueueForMeasure(); (transport as? GattTransport)?.priority(true)
        direct(Cmd.measureSpO2(true), "stream start")
        main.removeCallbacks(streamWatch); main.postDelayed(streamWatch, 15_000); changed()
    }
    fun stopStream(why: String) {
        val src = streamSource ?: return
        streamSource = null; main.removeCallbacks(streamWatch)
        direct(Cmd.measureSpO2(false), "stream stop")
        TrafficLog.add("SYS", "streamSummary", null, streamSummary(why, src))
        resumeQueueAfterMeasure(); changed()
    }
    fun streamSummary(why: String, src: String = streamSource ?: "") : String {
        val dur = (System.currentTimeMillis() - streamStartedAt) / 1000
        val g = streamGaps.sorted()
        return "$src: $why after ${dur}s, $streamCount values, ${streamFails} fails, $streamRestarts restarts" +
            (if (g.isNotEmpty()) ", gap median ${g[g.size / 2] / 1000.0}s min ${g.first() / 1000.0}s max ${g.last() / 1000.0}s, ${"%.2f".format(java.util.Locale.US, streamCount * 60.0 / maxOf(1L, dur))} values/min" else "")
    }
    private fun onStreamValue(v: Double) {
        val now = System.currentTimeMillis()
        if (streamLastAt > 0) streamGaps += now - streamLastAt
        streamLastAt = now; streamCount++
        val ok = com.rlong.sleephub.ring.auto.AutoSpo2Logic.valid(v)
        TrafficLog.add("SYS", "stream", null, "#$streamCount ${v.toInt()}${if (ok) "" else " (invalid, dropped)"} t+${(now - streamStartedAt) / 1000}s gap ${if (streamGaps.isEmpty()) "-" else "${streamGaps.last() / 1000.0}s"} (${"%.2f".format(java.util.Locale.US, streamCount * 60_000.0 / maxOf(1L, now - streamStartedAt))}/min)")
        if (ok) db.addSamples(listOf(Sample(Metric.SPO2, now, v, streamSource!!)), demo)
        restartStreamSoon(STREAM_RESTART_GAP_MS)
    }
    /** The ring ends each SpO2 measurement itself (LED off after one result), so "continuous" = back-to-back measurements. */
    private fun restartStreamSoon(ms: Long) {
        main.postDelayed({ if (streamSource != null && measuring == null && state == State.CONNECTED) { streamRestarts++; streamCycleStart = System.currentTimeMillis()
            // explicit stop first: a bare re-start 0.5 s after a result was ignored by the ring
            direct(Cmd.measureSpO2(false), "back-to-back stop"); main.postDelayed({ if (streamSource != null && measuring == null) direct(Cmd.measureSpO2(true), "back-to-back #$streamRestarts") }, 700) } }, ms)
    }
    private var streamCycleStart = 0L

    /** Measurements pre-empt any queued work (e.g. a long full sync): the queue is suspended with its state kept, then resumed after the measure. */
    private var pausedForMeasure = false
    private fun pauseQueueForMeasure() {
        main.post {
            if (!pausedForMeasure && cmdQueue.size > 0) { pausedForMeasure = true; cmdQueue.suspend(); TrafficLog.add("SYS", "queuePause", null, "measure pre-empts ${cmdQueue.size} queued commands") }
        }
    }
    private fun resumeQueueAfterMeasure() {
        main.postDelayed({
            if (measuring == null && !fullSyncRunning && streamSource == null) (transport as? GattTransport)?.priority(false)
            if (measuring == null && pausedForMeasure && streamSource == null) { pausedForMeasure = false; TrafficLog.add("SYS", "queueResume", null, "${cmdQueue.size} queued commands"); if (state == State.CONNECTED) cmdQueue.resume() }
        }, 1200)
    }
    /** Write outside the queue (measure start/stop only). */
    private fun direct(f: ByteArray, note: String) {
        main.post { if (state == State.CONNECTED || demo) { TrafficLog.add("TX", describeFrame(f), f, "$note (direct)"); (transport as? GattTransport)?.noResponse = preferNoResponse; transport?.write(f) } }
    }
    private fun sendStart(m: Metric) {
        val f = when (m) {
            Metric.HEART_RATE -> Cmd.measureHeartRate(true); Metric.HRV -> Cmd.measureHrv(true)
            Metric.SPO2 -> Cmd.measureSpO2(true); Metric.STRESS -> Cmd.measureStress(true)
            Metric.TEMP -> Cmd.measureTemp(true); Metric.BP_SYS, Metric.BP_DIA -> Cmd.measureBloodPressure(true)
            else -> return
        }
        direct(f, "start")
    }
    private fun sendStop(m: Metric) {
        val f = when (m) {
            Metric.HEART_RATE -> Cmd.measureHeartRate(false); Metric.HRV -> Cmd.measureHrv(false)
            Metric.SPO2 -> Cmd.measureSpO2(false); Metric.STRESS -> Cmd.measureStress(false)
            Metric.TEMP -> Cmd.measureTemp(false); Metric.BP_SYS, Metric.BP_DIA -> Cmd.measureBloodPressure(false)
            else -> return
        }
        direct(f, "stop")
    }
    fun tryMeasureVariant(m: Metric, v: Cmd.MeasureVariant) {
        preferNoResponse = v != Cmd.MeasureVariant.V_STOCK_DEFAULT
        if (state != State.CONNECTED) { measureMessage = "Not connected"; changed(); return }
        measuring?.let { if (it != m) sendStop(it) }
        measuring = m; measureSource = "live"; measureResult = null
        measureDeadlineMs = System.currentTimeMillis() + measureTimeoutMs(m)
        measureMessage = "Measuring ${m.label} (${v.name})…"
        pauseQueueForMeasure(); for (f in Cmd.measureVariant(m, v)) direct(f, v.name)
        main.removeCallbacks(measureTimeoutRunnable)
        main.postDelayed(measureTimeoutRunnable, measureTimeoutMs(m))
        main.removeCallbacks(tickRunnable); main.post(tickRunnable)
        changed()
    }
    fun measureRemainingSec(): Int {
        if (measuring == null || measureDeadlineMs == 0L) return 0
        return ((measureDeadlineMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
    }
    fun findRing(on: Boolean) = send(Cmd.findRing(on))
    fun readSteps() { if (measuring != null || streamSource != null) return; transport?.read(Uuids.STEPS) }

    init {
        pairedAddress = prefs.getString("paired_mac", null)
        pairedName = prefs.getString("paired_name", null)
    }
}

@SuppressLint("MissingPermission")
class GattTransport(
    private val ctx: Context, private val device: BluetoothDevice,
    private val onNotify: (UUID, ByteArray) -> Unit,
    private val onReady: () -> Unit, private val onLost: (String) -> Unit,
    private val autoConnect: Boolean = false,
    private val onWriteDone: (Int) -> Unit = {},
) : Transport {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val pendingNotify = ArrayDeque<BluetoothGattCharacteristic>()
    private val main = Handler(Looper.getMainLooper())

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            TrafficLog.add("SYS", "state", null, "status=$status new=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) onLost("Disconnected ($status)")
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            TrafficLog.add("SYS", "services", null, "status=$status count=${g.services.size}")
            val svc = g.services.firstOrNull { it.uuid.toString().contains("fdda") }
            if (svc == null) { onLost("No FDDA service (not a MoYoung ring?)"); return }
            writeChar = svc.getCharacteristic(Uuids.WRITE)
                ?: svc.characteristics.firstOrNull { it.uuid.toString().contains("fdd2") }
            val notify = listOfNotNull(
                svc.characteristics.firstOrNull { it.uuid.toString().contains("fdd3") },
                svc.characteristics.firstOrNull { it.uuid.toString().contains("fdd1") },
                g.getService(Uuids.BATTERY_SVC)?.getCharacteristic(Uuids.BATTERY),
                g.getService(Uuids.HR_SVC)?.getCharacteristic(Uuids.HR),
            )
            pendingNotify.clear(); pendingNotify.addAll(notify)
            enableNext()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            TrafficLog.add("SYS", "cccd", null, "${d.characteristic.uuid} status=$status")
            enableNext()
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onNotify(c.uuid, c.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid, value)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readBusyUntil = 0
            if (status == BluetoothGatt.GATT_SUCCESS) onNotify(c.uuid, c.value ?: return)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val meaning = when (status) {
                BluetoothGatt.GATT_SUCCESS -> "OK"
                3 -> "WRITE_NOT_PERMITTED (try NO_RESPONSE)"
                5 -> "INSUFFICIENT_AUTH"
                13 -> "INVALID_LENGTH"
                else -> "err"
            }
            TrafficLog.add("SYS", "writeDone", null, "status=$status $meaning")
            onWriteDone(status)
        }
        /** Hidden framework callback (API 26+), overridden by name: logs the negotiated connection interval. */
        @Suppress("unused") fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            TrafficLog.add("SYS", "connParams", null, "interval=${interval * 1.25}ms latency=$latency timeout=${timeout * 10}ms status=$status")
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            TrafficLog.add("SYS", "mtu", null, "mtu=$mtu status=$status")
            if (!readySent) { readySent = true; main.post { onReady() } }
        }
    }

    private fun enableNext() {
        val g = gatt ?: return
        val c = pendingNotify.removeFirstOrNull()
        if (c == null) {
            g.requestMtu(247)
            // start the queue only after the MTU exchange (writes during it fail with ok=false); 3 s fallback
            main.postDelayed({ if (!readySent) { readySent = true; onReady() } }, 3000)
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Uuids.CCCD)
        if (d != null) {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        } else enableNext()
    }

    @Volatile private var readySent = false
    fun priority(high: Boolean) {
        val ok = runCatching { gatt?.requestConnectionPriority(if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }.getOrNull()
        TrafficLog.add("SYS", "connPriority", null, (if (high) "HIGH" else "BALANCED") + " ok=$ok")
    }
    var noResponse = true
    /** Called on the main thread when a frame could not be written after all retries (GATT stayed busy). */
    var onWriteGaveUp: ((ByteArray) -> Unit)? = null
    @Volatile private var readBusyUntil = 0L
    override fun write(frame: ByteArray) = writeAttempt(frame, 0)
    private fun writeAttempt(frame: ByteArray, attempt: Int) {
        val g = gatt; val c = writeChar
        if (g == null || c == null) {
            TrafficLog.add("SYS", "writeFail", frame, "no gatt/char")
            return
        }
        // Real-ring log: WRITE_TYPE_DEFAULT → status=3 WRITE_NOT_PERMITTED on FDD2.
        // Stock SDK (BleWriteCharacteristicProxy) uses WRITE_TYPE_NO_RESPONSE (=1) for protocol V1.
        val props = c.properties
        // Prefer the type the caller asked for; fall back if the characteristic lacks that property.
        val hasNoResp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val hasWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        c.writeType = when {
            noResponse && hasNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            !noResponse && hasWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            hasNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        c.value = frame
        // a GATT read (FDD1 live steps) still in flight makes writeCharacteristic return false: wait for it
        if (System.currentTimeMillis() < readBusyUntil && attempt < WRITE_TRIES - 1) { main.postDelayed({ writeAttempt(frame, attempt + 1) }, 150); return }
        val ok = g.writeCharacteristic(c)
        TrafficLog.add("SYS", "write", null, "ok=$ok type=${c.writeType} props=0x${props.toString(16)}" + if (attempt > 0) " attempt=${attempt + 1}" else "")
        // GATT busy (another op in flight): try again shortly instead of dropping the frame
        if (!ok && attempt < WRITE_TRIES - 1) main.postDelayed({ writeAttempt(frame, attempt + 1) }, 150)
        else if (!ok) { TrafficLog.add("SYS", "writeGaveUp", frame, "GATT busy after $WRITE_TRIES attempts"); onWriteGaveUp?.invoke(frame) }
    }
    companion object { const val WRITE_TRIES = 40 }
    override fun read(uuid: UUID) {
        val g = gatt ?: return
        val c = g.services.asSequence().flatMap { it.characteristics.asSequence() }.firstOrNull { it.uuid == uuid } ?: return
        readBusyUntil = System.currentTimeMillis() + 3_000
        if (!g.readCharacteristic(c)) readBusyUntil = 0
    }
    override fun close() { try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}; gatt = null }

    init {
        TrafficLog.add("SYS", "connectGatt", null, device.address)
        gatt = device.connectGatt(ctx, autoConnect, cb, BluetoothDevice.TRANSPORT_LE)
    }
}
