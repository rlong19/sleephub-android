package com.rlong.sleephub.oxi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.PowerManager
import com.rlong.sleephub.core.Timeline
import com.rlong.sleephub.core.TrafficLogger
import java.util.concurrent.ConcurrentHashMap

data class FoundOxi(val address: String, val name: String?, val rssi: Int, val emay: Boolean = true, val bonded: Boolean = false)

/**
 * EMAY SleepO2 BLE link (third concurrent device). Own HandlerThread + partial wakelock while a link is wanted, so the
 * 1.5 s heartbeat keeps running with the screen off (the FGS keeps the process alive). Persistent auto-reconnect.
 * Settings keys (separate from Zeo/ring): oxi_mac, oxi_name.
 */
@SuppressLint("MissingPermission")
class Oximeter(private val ctx: Context, private val prefs: SharedPreferences, val db: OxiDb, val log: TrafficLogger, private val timeline: Timeline) {
    enum class State { DISCONNECTED, CONNECTING, STREAMING }
    @Volatile var state = State.DISCONNECTED; private set
    @Volatile var battery: Int? = null; private set
    @Volatile var latest: OxiReading? = null; private set
    @Volatile var lastFrameAt = 0L; private set
    @Volatile var userDisconnected = false; private set
    @Volatile var lastError = ""
    val listeners = ArrayList<() -> Unit>()
    val found = ConcurrentHashMap<String, FoundOxi>()
    @Volatile var scanning = false; private set

    val savedAddress get() = prefs.getString("oxi_mac", null)?.takeIf { it.isNotEmpty() }
    val savedName get() = prefs.getString("oxi_name", null)
    private val th = HandlerThread("oximeter").apply { start() }
    private val h = object : Handler(th.looper) {
        override fun dispatchMessage(msg: android.os.Message) {
            try { super.dispatchMessage(msg) }
            catch (e: SecurityException) { lastError = com.rlong.sleephub.core.BtPerms.MSG; try { log.add("SYS", "SecurityException", null, e.message ?: "") } catch (_: Throwable) {}; changed() }
        }
    }
    private var gatt: BluetoothGatt? = null
    private var writeCh: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>(); private var writing = false
    private val buf = ArrayList<OxiReading>()
    /** In-memory ring buffer for the live graph (30 min at 1 Hz). Backfilled once from the DB. */
    private val live = ArrayDeque<OxiReading>(); private val LIVE_MAX = 30 * 60 + 60
    @Volatile private var backfilled = false
    fun liveReadings(windowMs: Long = 30 * 60_000L): List<OxiReading> {
        if (!backfilled) { backfilled = true
            try { val now = System.currentTimeMillis(); val old = db.range(now - 30 * 60_000L, now)
                synchronized(live) { val first = live.firstOrNull()?.time ?: Long.MAX_VALUE
                    old.map { it.sanitized() }.filter { it.time < first }.asReversed().forEach { live.addFirst(it) }
                    while (live.size > LIVE_MAX) live.removeFirst() } } catch (_: Exception) {} }
        val from = System.currentTimeMillis() - windowMs
        return synchronized(live) { live.filter { it.time >= from } }
    }
    private var wl: PowerManager.WakeLock? = null
    private var retry = 0

    val status: String get() = when (state) {
        State.DISCONNECTED -> if (savedAddress == null) "no oximeter saved" else "disconnected" + if (lastError.isNotEmpty()) " ($lastError)" else ""
        State.CONNECTING -> "connecting ${savedName ?: ""}"
        State.STREAMING -> "connected ${savedName ?: ""}"
    }
    val fingerOn get() = latest?.fingerOn == true && System.currentTimeMillis() - lastFrameAt < 4_000

    private fun changed() = listeners.toList().forEach { try { it() } catch (_: Exception) {} }
    private fun adapter() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    // ---------- picker scan: FF12 service OR name prefix; nothing else is ever listed ----------
    fun isEmay(name: String?, uuids: List<ParcelUuid>?, devName: String? = null) =
        uuids?.any { it.uuid == EmayProtocol.SERVICE } == true || EmayProtocol.isEmayName(name, devName)
    /** "Show all BLE devices": list every advertiser so the user can pick the oximeter even if its name is unexpected. */
    @Volatile var showAll = false
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val local = r.scanRecord?.deviceName
            val dn = try { r.device.name } catch (_: SecurityException) { null }
            val n = local ?: dn
            val emay = isEmay(local, r.scanRecord?.serviceUuids, dn)
            if (!emay && !showAll) return
            found[r.device.address] = FoundOxi(r.device.address, n ?: dn, r.rssi, emay); changed()
            if (state == State.DISCONNECTED && !userDisconnected && r.device.address.equals(savedAddress, true)) h.post { connectInternal() }
        }
        override fun onScanFailed(errorCode: Int) { lastError = "scan failed $errorCode"; scanning = false; changed() }
    }
    fun startScan(ms: Long = 30_000) {
        if (!com.rlong.sleephub.core.BtPerms.canScan(ctx) || !com.rlong.sleephub.core.BtPerms.canConnect(ctx)) { lastError = com.rlong.sleephub.core.BtPerms.MSG; changed(); return }
        val sc = adapter()?.bluetoothLeScanner ?: run { lastError = "Bluetooth off"; changed(); return }
        found.clear()
        // Bonded/connected-to-Android oximeters may stop advertising: list matching bonded devices too (rssi unknown = -999).
        try { adapter()?.bondedDevices?.forEach { d -> val dn = try { d.name } catch (_: SecurityException) { null }
            if (EmayProtocol.isEmayName(dn) || (showAll && d.type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC)) found[d.address] = FoundOxi(d.address, dn, -999, EmayProtocol.isEmayName(dn), true) } } catch (_: Exception) {}
        try { sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb); scanning = true } catch (e: Exception) { lastError = "scan: ${e.message}" }
        h.postDelayed({ stopScan() }, ms); changed()
    }
    fun stopScan() { try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}; scanning = false; changed() }

    fun select(address: String, name: String?) {
        prefs.edit().putString("oxi_mac", address).putString("oxi_name", name ?: "SleepO2").apply()
        disconnect(user = false); userDisconnected = false; connect()
    }
    fun forget() { disconnect(); prefs.edit().remove("oxi_mac").remove("oxi_name").apply(); changed() }

    fun connect() { userDisconnected = false; h.post { connectInternal() } }
    /** Watchdog entry from the service (every 15 s). */
    fun tryAutoConnect() { if (savedAddress != null && !userDisconnected) h.post {
        if (state == State.STREAMING && lastFrameAt > 0 && System.currentTimeMillis() - lastFrameAt > 30_000) { log.add("SYS", "stale", null, "no frames 30 s → reconnect"); closeGatt() }
        if (state == State.DISCONNECTED) connectInternal() } }

    private fun connectInternal() {
        val mac = savedAddress ?: return
        if (state != State.DISCONNECTED) return
        if (!com.rlong.sleephub.core.BtPerms.canConnect(ctx)) { lastError = com.rlong.sleephub.core.BtPerms.MSG; changed(); return }
        try { connectGattChecked(mac) } catch (e: SecurityException) { lastError = com.rlong.sleephub.core.BtPerms.MSG; state = State.DISCONNECTED; gatt = null; release(); changed() }
    }
    private fun connectGattChecked(mac: String) {
        val dev: BluetoothDevice = try { adapter()?.getRemoteDevice(mac) } catch (_: Exception) { null } ?: return
        acquire(); state = State.CONNECTING; changed(); log.add("SYS", "connect", null, mac)
        gatt = if (Build.VERSION.SDK_INT >= 26) dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h) else dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        h.postDelayed({ if (state == State.CONNECTING) { lastError = "connect timeout"; closeGatt() } }, 25_000)
    }

    fun disconnect(user: Boolean = true) { if (user) userDisconnected = true; h.post {
        writeCh?.let { try { send(EmayProtocol.command(EmayProtocol.STOP_REALTIME)) } catch (_: Exception) {} }
        h.postDelayed({ closeGatt(); if (userDisconnected) release() }, 300) } }

    private fun closeGatt() {
        h.removeCallbacks(hb); flush()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; writeCh = null; queue.clear(); writing = false
        if (state != State.DISCONNECTED) { state = State.DISCONNECTED; log.add("SYS", "disconnected", null) }
        latest = null; changed()
        if (!userDisconnected && savedAddress != null) { retry++; h.postDelayed({ connectInternal() }, (2_000L * retry).coerceAtMost(30_000L)) }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { h.post {
            if (newState == BluetoothProfile.STATE_CONNECTED) { log.add("SYS", "gatt connected", null); g.discoverServices() }
            else { lastError = "gatt status $status"; if (g == gatt) closeGatt() else g.close() } } }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { h.post {
            val s = g.getService(EmayProtocol.SERVICE); val w = s?.getCharacteristic(EmayProtocol.WRITE); val n = s?.getCharacteristic(EmayProtocol.NOTIFY)
            if (w == null || n == null) { lastError = "FF12 service not found"; userDisconnected = true; closeGatt(); return@post }
            writeCh = w; g.setCharacteristicNotification(n, true)
            val d = n.getDescriptor(EmayProtocol.CCCD)
            if (d == null) startSequence() else {
                @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } }
        } }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { h.post { startSequence() } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { h.post { writing = false; pump() } }
        @Deprecated("API<33") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { @Suppress("DEPRECATION") val v = c.value ?: return; h.post { rx(v.copyOf()) } }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { h.post { rx(value) } }
    }

    private fun startSequence() {
        EmayProtocol.START_SEQUENCE.forEach { send(it) }
        state = State.STREAMING; retry = 0; lastError = ""; changed()
        h.removeCallbacks(hb); h.postDelayed(hb, EmayProtocol.HEARTBEAT_MS)
        h.postDelayed(batt, 60_000)
    }
    private val hb = object : Runnable { override fun run() { if (state != State.STREAMING) return; if (!syncing) send(EmayProtocol.command(EmayProtocol.HEARTBEAT)); h.postDelayed(this, EmayProtocol.HEARTBEAT_MS) } }
    private val batt = object : Runnable { override fun run() { if (state != State.STREAMING) return; if (!syncing) send(EmayProtocol.command(EmayProtocol.GET_BATTERY)); h.postDelayed(this, 10 * 60_000L) } }

    /** Only whitelisted read/stream frames can ever be written. */
    private fun send(frame: ByteArray) {
        val why = EmayProtocol.guard(frame, clockAllowed = clockArmed)
        if (why != null) { log.add("SYS", "BLOCKED", frame, why); return }
        queue.addLast(frame); pump()
    }
    @Suppress("DEPRECATION")
    private fun pump() {
        if (writing) return
        val g = gatt ?: return; val w = writeCh ?: return; val f = queue.removeFirstOrNull() ?: return
        val wt = if (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(w, f, wt) == BluetoothGatt.GATT_SUCCESS
            else { w.writeType = wt; w.value = f; g.writeCharacteristic(w) }
        if (f[0] != EmayProtocol.HEARTBEAT[0]) log.add("TX", "cmd", f, if (ok) "" else "write failed")
        writing = ok
        if (ok) h.postDelayed({ if (writing) { writing = false; pump() } }, 1000) else h.postDelayed({ pump() }, 200)
    }

    private fun rx(v: ByteArray) {
        if (syncing) { if (v.size <= 24) log.add("RX", "sync", v); rxq.offer(v); return }
        val r = EmayProtocol.parse(v)?.sanitized()
        if (r == null) {
            log.add("RX", "other", v)
            EmayProtocol.parseBattery(v)?.let { battery = it; changed() }
            return
        }
        latest = r; lastFrameAt = r.time
        synchronized(buf) { buf += r }
        synchronized(live) { live.addLast(r); while (live.size > LIVE_MAX) live.removeFirst() }
        if (buf.size >= 30) flush()
        changed()
    }
    fun flush() { val l = synchronized(buf) { buf.toList().also { buf.clear() } }; if (l.isEmpty()) return
        try { db.add(l, "ble") } catch (e: Exception) { log.add("SYS", "db error", null, e.message ?: "") }
        l.last().let { if (it.spo2 != null) timeline.add("oximeter", "spo2", it.spo2, time = it.time); if (it.pulse != null) timeline.add("oximeter", "pulse", it.pulse, time = it.time) } }

    // ---------- v0.3.1 read-only stored-history sync ----------
    @Volatile var syncing = false; private set
    @Volatile var syncStatus = ""; private set
    @Volatile private var clockArmed = false
    private val rxq = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val acc = java.io.ByteArrayOutputStream()

    /** Starts the sync on a worker thread. Returns an error message or null. */
    fun syncHistory(onDone: (String) -> Unit = {}): String? {
        if (state != State.STREAMING) return "Oximeter not connected"
        if (syncing) return "Sync already running"
        syncing = true; progress("starting")
        Thread({ val msg = try { runSync() } catch (e: Throwable) { "Sync failed: ${e.message}" } finally {
                clockArmed = false; syncing = false; rxq.clear()
                h.post { if (state == State.STREAMING) { send(EmayProtocol.command(EmayProtocol.START_REALTIME)); send(EmayProtocol.command(EmayProtocol.GET_BATTERY)) } } }
            progress(msg); log.add("SYS", "history sync", null, msg); onDone(msg) }, "oxi-sync").start()
        return null
    }
    private fun progress(s: String) { syncStatus = s; h.post { changed() } }
    private fun tx(f: ByteArray) = h.post { send(f) }
    private fun drain() { rxq.clear(); acc.reset() }
    /** Drop stray bytes at a frame boundary: leading 00, 8-byte EF frames, valid realtime EB 01 frames. */
    private fun stripHead() { while (true) { val b = acc.toByteArray(); if (b.isEmpty()) return
        val n = when { b[0].toInt() == 0 -> 1; (b[0].toInt() and 0xFF) == 0xEF && b.size >= 8 -> 8
            b.size >= 8 && EmayProtocol.parse(b.copyOf(8)) != null -> 8; else -> 0 }
        if (n == 0) return; acc.reset(); acc.write(b, n, b.size - n) } }
    /** Wait for exactly [len] bytes (reassembling notification chunks); null on timeout or disconnect. */
    private fun await(len: Int, timeoutMs: Long): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) { stripHead()
            if (acc.size() >= len) { val b = acc.toByteArray(); acc.reset(); acc.write(b, len, b.size - len); return b.copyOf(len) }
            if (state != State.STREAMING) throw IllegalStateException("disconnected")
            val left = end - System.currentTimeMillis(); if (left <= 0) return null
            rxq.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { acc.write(it) } }
    }
    private fun ask(cmd: ByteArray, len: Int, timeout: Long = 4000): ByteArray? { drain(); tx(cmd); return await(len, timeout) }

    private fun runSync(): String {
        tx(EmayProtocol.command(EmayProtocol.STOP_REALTIME)); Thread.sleep(800); drain()
        progress("hello")
        var hello = ask(EmayProtocol.command(EmayProtocol.HELLO), 2, 10_000)
        if (hello == null) hello = ask(EmayProtocol.command(EmayProtocol.HELLO), 2, 10_000)
        if (hello == null) return "No reply to hello"
        Thread.sleep(500); drain()
        val st = ask(EmayProtocol.command(EmayProtocol.DEVICE_STATE), 13, 3000)
        val busy: Boolean? = st?.takeIf { (it[0].toInt() and 0xFF) == 0xFE && it[1].toInt() == 5 && EmayHistory.ok(it) }?.let { (it[4].toInt() and 0x7F).let { s -> s == 1 || s == 4 } }
        drain()
        if (prefs.getBoolean("oxi_set_clock", false)) {
            if (busy == false) { clockArmed = true; ask(EmayProtocol.setClock(java.util.Calendar.getInstance()), 3, 3000); clockArmed = false; log.add("SYS", "clock set", null) }
            else log.add("SYS", "clock NOT set", null, "device state ${if (busy == null) "unknown" else "busy/recording"}")
        }
        ask(EmayProtocol.command(EmayProtocol.GET_BATTERY), 3, 3000)?.let { EmayProtocol.parseBattery(it)?.let { b -> battery = b } }
        progress("storage summary")
        val sum = ask(EmayProtocol.command(EmayProtocol.STORAGE_SUMMARY), 15, 4000)?.let { EmayHistory.summary(it) } ?: return "No storage summary reply"
        if (sum.total == 0) return "Oximeter memory is empty"
        val segs = ArrayList<EmayHistory.Segment>()
        for (i in 0 until 300) { progress("listing recordings ${segs.size + 1}/${sum.total}")
            val f = ask(EmayProtocol.command(EmayProtocol.NEXT_SEGMENT), 21, 4000) ?: break
            val sg = EmayHistory.segment(f) ?: break
            segs += sg; if (sg.last) break; Thread.sleep(300) }
        if (segs.isEmpty()) return "Could not list recordings"
        var got = 0; var rows = 0; var skipped = 0; val fails = ArrayList<String>()
        for ((k, sg) in segs.withIndex()) {
            if (sg.length <= 0 || db.hasSegment(sg.start, sg.length)) { skipped++; continue }
            val tag = "rec ${k + 1}/${segs.size} (${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(sg.start))}, ${sg.length / 60} min)"
            val sp = download(1, sg, "$tag SpO2") ?: run { fails += tag; null }
            Thread.sleep(300)
            val pr = if (sp != null) download(2, sg, "$tag pulse") else null
            if (sp == null || pr == null) { if (sp != null) fails += tag; continue }
            val n = db.addDedup(EmayHistory.readings(sg.start, sp, pr), "device_history")
            db.markSegment(sg.start, sg.length, sg.item, n); got++; rows += n
            if (sg.clockUnset) fails += "$tag: device clock was unset (year 2000/2037) — timestamps wrong"
        }
        return "History sync: ${segs.size} recordings on device, $got downloaded ($rows new 1 Hz rows), $skipped already stored" +
            (if (fails.isNotEmpty()) "; problems: " + fails.joinToString("; ") else "") + ". Nothing was deleted on the device."
    }

    /** Download one stream with per-packet validation and abort/resume (9D 02 …, wait 5 s, resume at good packets). */
    private fun download(type: Int, sg: EmayHistory.Segment, label: String): List<Int?>? {
        val n = EmayHistory.packets(sg.length); val good = ArrayList<ByteArray>(n); var errors = 0
        val totalTimeout = (60_000 + n * 150L)
        while (good.size < n) {
            drain(); tx(EmayProtocol.download(type, sg.item, good.size))
            val t0 = System.currentTimeMillis(); var bad = false
            while (good.size < n) {
                progress("$label ${good.size}/$n packets")
                val p = await(EmayHistory.PACKET, (totalTimeout - (System.currentTimeMillis() - t0)).coerceIn(1000, 15_000))
                if (p == null || !EmayHistory.packetOk(p, type, sg.item, good.size)) { bad = true; log.add("SYS", "bad packet", p, "$label at ${good.size}"); break }
                good += p
            }
            if (!bad) break
            if (++errors > 5) return null
            tx(EmayProtocol.abort(type, sg.item)); Thread.sleep(5_000); drain()
        }
        return EmayHistory.decodeAll(good, sg.length)
    }

    private fun acquire() { if (wl?.isHeld == true) return
        wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleephub:oximeter").apply { setReferenceCounted(false); acquire() } }
    private fun release() { try { wl?.release() } catch (_: Exception) {}; wl = null }
    val active get() = state != State.DISCONNECTED || (savedAddress != null && !userDisconnected)
}
