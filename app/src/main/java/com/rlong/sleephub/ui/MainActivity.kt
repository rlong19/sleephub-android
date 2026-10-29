package com.rlong.sleephub.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.ble.FoundRing
import com.rlong.sleephub.ring.data.TrafficLog
import com.rlong.sleephub.ring.proto.Metric
import com.rlong.sleephub.service.SleepHubService
import com.rlong.sleephub.zeo.ZeoSafety
import com.rlong.sleephub.ring.ui.historyView
import com.rlong.sleephub.ring.ui.syncButtons
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private val app get() = app()
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var tab = 0
    private val tabs = listOf("Dashboard", "History", "Ring", "Zeo", "Zeo probe", "Timeline", "Raw logs", "Oximeter")
    private val h = Handler(Looper.getMainLooper())
    private var pending = false
    private val listener: () -> Unit = { if (!pending) { pending = true; h.postDelayed({ pending = false; refresh(false) }, 400) } }
    private val found = ConcurrentHashMap<String, FoundRing>()
    private var showAllBt = false
    private var oxiWinMin = 5
    private var lastHist = 0L
    private var wasSyncing = false; private var histPending = false
    private var historyKey: Long? = null
    /** Combined Zeo + ring History (v18 History screen extended). */
    private fun historyTab(): View {
        val mode = runCatching { com.rlong.sleephub.ring.data.WindowMode.valueOf(app.prefs.getString("history_window", "NOON")!!) }.getOrDefault(com.rlong.sleephub.ring.data.WindowMode.NOON)
        return historyView(historyKey, mode, { historyKey = it; refresh(true) }, { app.prefs.edit().putString("history_window", it.name).apply(); refresh(true) }) { refresh(true) }
    }
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        banner = demoBadge("DEMO")
        status = text("", 13f)
        tabBar = LinearLayout(this); content = FrameLayout(this)
        val root = vbox(banner, status, HorizontalScrollView(this).apply { addView(tabBar) })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ensurePerms()
    }
    override fun onResume() {
        super.onResume()
        app.ring.listeners += listener; app.zeo.listeners += listener; app.oxi.listeners += listener; app.timeline.listeners += listener
        com.rlong.sleephub.ring.ui.HistoryCache.invalidate()   // History reloads when the screen is opened
        refresh(true)
    }
    override fun onPause() {
        super.onPause()
        app.ring.listeners -= listener; app.zeo.listeners -= listener; app.oxi.listeners -= listener; app.timeline.listeners -= listener
        app.ring.stopScan(); app.oxi.stopScan()
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean { menu.add("Settings"); menu.add("Stop all & exit"); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.title) {
            "Settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> { startService(Intent(this, SleepHubService::class.java).setAction(SleepHubService.ACTION_STOP)); finish() }
        }
        return true
    }

    private fun ensurePerms() {
        val need = ArrayList<String>()
        fun want(p: String) { if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need += p }
        if (Build.VERSION.SDK_INT >= 31) { want(Manifest.permission.BLUETOOTH_SCAN); want(Manifest.permission.BLUETOOTH_CONNECT) }
        else { want(Manifest.permission.ACCESS_FINE_LOCATION) }
        if (Build.VERSION.SDK_INT >= 33) want(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else app.startLinks()
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (com.rlong.sleephub.core.BtPerms.canConnect(this)) app.startLinks() else toast(com.rlong.sleephub.core.BtPerms.MSG)
        refresh(true)
    }

    private fun refresh(rebuild: Boolean) {
        val r = app.ring; val z = app.zeo
        val demos = listOfNotNull(if (r.demo) "RING" else null, if (z.demo) "ZEO" else null)
        banner.visibility = if (demos.isEmpty()) View.GONE else View.VISIBLE
        banner.text = "DEMO DATA — simulated ${demos.joinToString(" + ")} (not a real device)"
        status.text = "Ring: ${r.status} · bat ${r.battery?.label ?: "?"}\nZeo: ${z.status}" + (z.hbState?.let { " · bat raw ${it.voltage} st ${it.voltageStatus} (${it.voltageLabel})" } ?: "") + "\n" + oxiLine()
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, n -> tabBar.addView(button(n) { tab = i; if (i == 1) com.rlong.sleephub.ring.ui.HistoryCache.invalidate(); refresh(true); if (i == 2) openRing() }.apply { setTextColor(if (i == tab) Color.CYAN else Color.WHITE) }) }
        // Live-updating tabs are rebuilt on every change; input tabs (probe) only on explicit navigation.
        // v0.3.12: History is NOT rebuilt on device events (that was the ~4 s refresh that broke pinch/pan); only on the
        // Refresh button, when a ring/oximeter sync finishes, when the screen is opened, or when the day changes.
        val syncing = r.fullSyncRunning || app.oxi.syncing
        if (wasSyncing && !syncing) histPending = true; wasSyncing = syncing
        val histReload = tab == 1 && !rebuild && !com.rlong.sleephub.ring.ui.Scrub.active && (histPending || com.rlong.sleephub.ring.ui.HistoryCache.dayChanged())
        if (histReload) com.rlong.sleephub.ring.ui.HistoryCache.invalidate()
        if (tab == 1 && (rebuild || histReload)) histPending = false
        if (rebuild || histReload || (tab != 4 && tab != 1)) {
            val y = (content.getChildAt(0) as? android.widget.ScrollView)?.scrollY ?: 0
            content.removeAllViews()
            if (tab == 1) lastHist = android.os.SystemClock.uptimeMillis()
            val v = when (tab) { 0 -> dashboard(); 1 -> historyTab(); 2 -> ringTab(); 3 -> zeoTab(); 4 -> probeTab(); 5 -> timelineTab(); 6 -> logsTab(); else -> oxiTab() }
            content.addView(v)
            if (!rebuild) (v as? android.widget.ScrollView)?.post { v.scrollTo(0, y) }
        }
    }

    // ---------- Dashboard ----------
    private fun dashboard(): View {
        val r = app.ring; val z = app.zeo
        val box = vbox()
        box.addView(header("Oximeter (EMAY SleepO2)"))
        box.addView(text(oxiLine(), 17f, if (app.oxi.state == com.rlong.sleephub.oxi.Oximeter.State.STREAMING) Color.WHITE else Color.LTGRAY))
        box.addView(header("Ring (MoYoung R24)" + if (r.demo) "  [DEMO]" else ""))
        box.addView(text(r.status, 13f, Color.LTGRAY))
        box.addView(text(Metric.values().filter { r.live.containsKey(it) }.joinToString("\n") { m -> "${m.label}: ${fmt(r.live[m]!!)} ${m.unit}" }.ifEmpty { "No live values yet (Ring tab → measure)" }, 17f))
        box.addView(LineView(this).apply { color = Color.rgb(255, 90, 90); pts = series("ring", "heart_rate") }, LinearLayout.LayoutParams(-1, dp(90)))
        box.addView(header("Zeo Mobile" + if (z.demo) "  [DEMO]" else ""))
        box.addView(text(z.status, 13f, Color.LTGRAY))
        val s = z.sleep; val st = z.hbState
        box.addView(text(buildString {
            append("Current stage: ${s?.currentStage?.label ?: "—"}\n")
            append("ZQ: ${s?.zq ?: "—"}   deep ${s?.timeInDeep ?: "—"} · light ${s?.timeInLight ?: "—"} · REM ${s?.timeInRem ?: "—"} · wake ${s?.timeInWake ?: "—"}\n")
            append("On head: ${st?.onHead ?: "?"}  docked: ${st?.docked ?: "?"}  algo: ${st?.algorithmLabel ?: "?"}\n")
            append("Battery: raw voltage ${st?.voltage ?: "?"} · status ${st?.voltageStatus ?: "?"} = ${st?.voltageLabel ?: "?"}   last event: ${z.lastEvent.ifEmpty { "—" }}\n")
            z.info?.let { append("Device: ${it.deviceName} model=${it.model} sn=${it.serial} sw=0x%08X hw=0x%08X".format(it.swVersion, it.hwVersion)) }
        }, 15f))
        box.addView(HypnoView(this).apply { stages = s?.base ?: emptyList() }, LinearLayout.LayoutParams(-1, dp(110)))
        box.addView(header("Combined (last 20 events)"))
        box.addView(text(app.timeline.snapshot().takeLast(20).reversed().joinToString("\n") { "${tf.format(Date(it.time))} ${it.source} ${it.key}=${it.value}" }, 12f).apply { typeface = Typeface.MONOSPACE })
        return scroll(box)
    }
    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
    private fun series(srcPrefix: String, key: String) = app.timeline.snapshot().filter { it.source.startsWith(srcPrefix) && it.key == key }
        .mapNotNull { r -> r.value.toDoubleOrNull()?.let { r.time to it } }.takeLast(300)

    /** Oximeter status row: connection · battery · live SpO2/PR · finger on/off. */
    private fun oxiLine(): String { val o = app.oxi; val l = o.latest
        return "Oximeter: ${o.status} · bat ${o.battery?.let { "$it%" } ?: "?"}" + when {
            o.state != com.rlong.sleephub.oxi.Oximeter.State.STREAMING -> ""
            l == null || !o.fingerOn -> " · finger OFF"
            else -> " · SpO2 ${l.spo2 ?: "–"}% · PR ${l.pulse ?: "–"} bpm · finger on" } }

    // ---------- Oximeter ----------
    @SuppressLint("MissingPermission")
    private fun oxiTab(): View {
        val o = app.oxi
        val box = vbox(header("EMAY SleepO2 oximeter"), text("${oxiLine()}\nSaved: ${o.savedName ?: "—"} ${o.savedAddress ?: ""}\nStored readings: ${runCatching { app.oxiDb.count() }.getOrDefault(0)}", 14f))
        box.addView(hbox(*listOf(1, 5, 30).map { m -> button(if (oxiWinMin == m) "[$m min]" else "$m min") { oxiWinMin = m; refresh(true) } }.toTypedArray()))
        box.addView(OxiGraphView(this).apply { windowMs = oxiWinMin * 60_000L; readings = o.liveReadings(windowMs) })
        box.addView(text("BLE, no pairing. Scan lists only EMAY devices (service FF12 or name SleepO2/EMAY). The oximeter is saved separately from the Zeo and ring. Only read/stream commands are ever sent.", 12f, Color.LTGRAY))
        box.addView(hbox(button(if (o.scanning) "Scanning…" else "Scan for oximeter") { o.startScan(); SleepHubService.start(this) },
            button("Reconnect") { if (o.savedAddress == null) toast("No saved oximeter — scan and pick it") else { o.connect(); SleepHubService.start(this) } },
            button("Disconnect") { o.disconnect(); SleepHubService.refresh(this) }))
        val f = o.found.values.sortedByDescending { it.rssi }
        if (o.scanning && f.isEmpty()) box.addView(text("Looking for EMAY devices… (long-press the oximeter button until it shows Bluetooth; if it is paired/connected in Android Bluetooth settings, it may stop advertising — try Show all BLE devices)", 13f, Color.YELLOW))
        box.addView(button(if (o.showAll) "Showing ALL BLE devices (tap: EMAY only)" else "Show all BLE devices") { o.showAll = !o.showAll; if (o.scanning) { o.stopScan(); o.startScan() }; refresh(true) })
        f.forEach { d -> box.addView(button("${if (d.emay) "" else "? "}${d.name ?: "(no name)"}  ${d.address}  ${if (d.bonded) "paired" else "${d.rssi} dBm"}") {
            AlertDialog.Builder(this).setTitle("Use this oximeter?").setMessage("${d.name ?: "(no name)"}\n${d.address}" + if (d.emay) "" else "\n\nNot recognised as EMAY. It will only work if it exposes the FF12 service; otherwise the app disconnects (read-only, nothing is written).")
                .setPositiveButton("Use") { _, _ -> o.stopScan(); o.select(d.address, d.name); SleepHubService.start(this); refresh(true) }.setNegativeButton("Cancel", null).show() }) }
        box.addView(hbox(button("Forget oximeter") { o.forget(); refresh(true) }, button("Import EMAY CSV…") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")), REQ_CSV) }))
        box.addView(header("Stored history (read-only)"))
        box.addView(button(if (o.syncing) "Syncing… ${o.syncStatus}" else "Sync oximeter history") { oxiSync() })
        if (o.syncStatus.isNotEmpty()) box.addView(text(o.syncStatus, 13f, if (o.syncing) Color.YELLOW else Color.rgb(180, 220, 255)))
        box.addView(text("Downloads recordings stored on the oximeter (SpO2 + pulse, 1 Hz), skips ones already saved, then resumes live mode. Never deletes anything on the device (delete commands are hard-blocked). Clock set is off unless enabled in Settings.", 12f, Color.GRAY))
        box.addView(header("Raw traffic"))
        box.addView(text(app.oxiLog.snapshot().takeLast(60).reversed().joinToString("\n"), 10f).apply { typeface = Typeface.MONOSPACE })
        return scroll(box)
    }
    fun oxiSync() { app.oxi.syncHistory { m -> runOnUiThread { toast(m); refresh(true) } }?.let { toast(it) } ?: toast("Oximeter history sync started") }
    @Deprecated("Activity API") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req != REQ_CSV || res != RESULT_OK) return
        val uri = data?.data ?: return
        Thread { val msg = try {
                val txt = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val r = com.rlong.sleephub.oxi.OxiCsv.parse(txt)
                app.oxiDb.add(r.readings, "csv")
                "Imported ${r.readings.size} readings" + (r.readings.firstOrNull()?.let { " (${TimelineFmt.f(it.time)} → ${TimelineFmt.f(r.readings.last().time)})" } ?: "") + if (r.warnings.isNotEmpty()) "; ${r.warnings.size} rows skipped" else ""
            } catch (e: Exception) { "Import failed: ${e.message}" }
            runOnUiThread { toast(msg); refresh(true) } }.start()
    }
    private companion object { const val REQ_CSV = 41 }

    // ---------- Ring ----------
    /** Compact ring panel; the full v18 ring app (Connect, Dashboard, per-metric charts, History, Raw log, Debug TX) is RingActivity. */
    @SuppressLint("MissingPermission")
    /** Ring tab button opens the full ring dashboard (Connect · Dashboard · charts · History · Raw log · Debug TX · probe). */
    private fun openRing() = startActivity(Intent(this, com.rlong.sleephub.ring.ui.RingActivity::class.java))
    private fun ringTab(): View {
        val r = app.ring
        val box = vbox(header("Ring"), text("${r.status}\nSaved: ${r.pairedName ?: "—"} ${r.pairedAddress ?: ""}  fw ${r.firmware.ifEmpty { "?" }}  bat ${r.battery?.label ?: "?"}", 14f))
        box.addView(button("Open full ring dashboard (Connect · charts · History · logs · testing)") { openRing() })
        box.addView(hbox(button("Reconnect") { if (r.pairedAddress == null) toast("No saved ring — pair it in Ring screens → Connect") else { r.tryAutoConnect(); SleepHubService.start(this) } },
            button("Disconnect") { r.disconnect(); SleepHubService.refresh(this) },
))
        box.addView(syncButtons(r))
        if (r.syncSummary.isNotEmpty()) box.addView(text(r.syncSummary, 12f, Color.LTGRAY))
        box.addView(header("Measure"))
        val ms = listOf(Metric.HEART_RATE, Metric.SPO2, Metric.HRV, Metric.STRESS, Metric.TEMP)
        ms.chunked(3).forEach { row -> box.addView(hbox(*row.map { m -> button(m.label) { r.startMeasure(m) } }.toTypedArray())) }
        box.addView(hbox(button("Cancel measure") { r.cancelMeasure("user cancel") }, button("History") { tab = 1; com.rlong.sleephub.ring.ui.HistoryCache.invalidate(); refresh(true) }))
        r.measuring?.let { box.addView(text("⏱ ${it.label}: ${r.measureRemainingSec()} s — ${r.measureMessage}", 15f, Color.YELLOW)) }
        val a = com.rlong.sleephub.ring.auto.AutoSpo2
        box.addView(header("App auto SpO2"))
        box.addView(text(a.stateLine(this), 13f, Color.rgb(180, 220, 255)))
        box.addView(button(if (a.sleepingNow(this)) "I'm awake (stop sleep SpO2)" else "Sleeping now (start sleep SpO2)") { a.setSleepingNow(this, !a.sleepingNow(this)); refresh(true) })
        return scroll(box)
    }

    // ---------- Zeo ----------
    @SuppressLint("MissingPermission")
    private fun zeoTab(): View {
        val z = app.zeo
        val box = vbox(header("Zeo Mobile headband"), text("${z.status}\nSaved: ${z.savedName ?: "—"} ${z.savedAddress ?: ""}", 14f))
        box.addView(text("Pair the headband first in Android Settings → Bluetooth (PIN 0000; name usually \"Zeo\", MAC starts 4C:98:EF). Then pick it below. SleepHub dials out AND listens as \"Zeo Android\" so the headband can dial in.", 12f, Color.LTGRAY))
        box.addView(button("Connect saved headband ${z.savedName ?: ""} ${z.savedAddress ?: ""}") { val m = z.savedAddress; if (m == null) toast("No saved headband") else { z.connect(m, z.savedName); SleepHubService.start(this) } })
        box.addView(hbox(button("Disconnect") { z.disconnect(); SleepHubService.refresh(this) }, button("Forget") { z.forget() }, button("Pull now") {
            z.send(com.rlong.sleephub.zeo.Hmsg.command(6), "manual"); z.send(com.rlong.sleephub.zeo.Hmsg.command(8), "manual") }))
        box.addView(button("Time sync (as official app; only if algorithm idle)") { z.startTimeSync() })
        box.addView(header("Paired devices"))
        val ad = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = try { ad?.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        if (bonded.isEmpty()) box.addView(text("No paired devices (or Bluetooth permission missing).", 13f, Color.LTGRAY))
        // Zeo headbands only (OUI 4C:98:EF or name contains "zeo"); others hidden unless "Show all" (previously a SmartSolar device was picked by mistake).
        fun isZeo(d: android.bluetooth.BluetoothDevice) = d.address.startsWith("4C:98:EF", true) || (try { d.name } catch (_: SecurityException) { null })?.contains("zeo", true) == true
        val shown = bonded.filter { showAllBt || isZeo(it) }.sortedWith(compareByDescending<android.bluetooth.BluetoothDevice> { it.address.startsWith("4C:98:EF", true) }.thenBy { it.address })
        if (shown.isEmpty() && bonded.isNotEmpty()) box.addView(text("No paired Zeo headband (4C:98:EF…) found.", 13f, Color.YELLOW))
        shown.forEach { d ->
            val zeoLike = d.address.startsWith("4C:98:EF", true)
            box.addView(button("${if (zeoLike) "★ " else "⚠ "}${d.name ?: "(no name)"}  ${d.address}${if (zeoLike) "  (Zeo OUI)" else "  (NOT a Zeo OUI)"}") { z.connect(d.address, d.name); SleepHubService.start(this) })
        }
        val hidden = bonded.size - bonded.count { isZeo(it) }
        if (hidden > 0) box.addView(button(if (showAllBt) "Hide non-Zeo devices" else "Show all paired devices ($hidden non-Zeo hidden)") { showAllBt = !showAllBt; refresh(true) })
        box.addView(header("DEMO"))
        if (app.zeoDemoEnabled) box.addView(button("Start DEMO headband (simulated data)") { z.connectDemo(); SleepHubService.start(this) })
        else box.addView(text("Simulated Zeo source is OFF (enable in Settings → \"Zeo DEMO source\").", 13f, Color.LTGRAY))
        z.sleep?.let { s ->
            box.addView(header("Last sleep report"))
            box.addView(text("start ${s.startOfNight} end ${s.endOfNight} (hb clock s)  base epochs ${s.base.size}  display ${s.display.size}\nZQ ${s.zq} awakenings ${s.awakenings} timeToZ ${s.timeToZ} totalZ ${s.totalZ} valid ${s.valid} insufficient ${s.insufficientData}\nclock offset: ${z.clockOffsetMs?.let { "$it ms" } ?: "unknown"}", 13f))
            box.addView(HypnoView(this).apply { stages = s.display }, LinearLayout.LayoutParams(-1, dp(80)))
        }
        return scroll(box)
    }

    // ---------- Probe ----------
    private fun probeTab(): View {
        val z = app.zeo
        val out = text("", 11f).apply { typeface = Typeface.MONOSPACE }
        fun showTail() { out.text = z.log.snapshot().takeLast(40).reversed().joinToString("\n") }
        val box = vbox(header("Zeo command probe"),
            text("Only SAFE-READ / SAFE-TOGGLE commands are listed. Dangerous ones (factory reset, reboot, BT lock, rename/PIN, sleep start/stop, demo mode, clock write, all TEST_* 150–173) are not offered and are blocked in the custom box. Every TX and the raw reply hex go to logs/zeo_traffic.log.", 12f, Color.LTGRAY))
        if (z.state != com.rlong.sleephub.zeo.ZeoSession.State.CONNECTED) box.addView(text("Not connected — sends will be logged as failed.", 13f, Color.YELLOW))
        ZeoSafety.PROBES.forEach { p -> box.addView(button("[${p.klass}] ${p.label}") { z.sendProbe(p.build(), p.label)?.let { toast(it) }; h.postDelayed({ showTail() }, 700); showTail() }) }
        box.addView(header("Custom (type byte + body, hex)"))
        val et = EditText(this).apply { hint = "e.g. 01 06 00 00 00  (COMMAND QUERY_STATE)"; inputType = InputType.TYPE_CLASS_TEXT; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        box.addView(et)
        box.addView(button("Send custom (safety-checked)") {
            val (frame, msg) = ZeoSafety.fromUserHex(et.text.toString())
            if (frame == null) { z.log.add("SYS", "BLOCKED", null, "custom '${et.text}': $msg"); toast(msg) } else z.sendProbe(frame, "custom")?.let { toast(it) }
            h.postDelayed({ showTail() }, 700); showTail()
        })
        box.addView(button("Refresh log") { showTail() })
        box.addView(out); showTail()
        return scroll(box)
    }

    // ---------- Timeline ----------
    private fun timelineTab(): View {
        val rows = app.timeline.snapshot()
        val box = vbox(header("Combined timeline (${rows.size} rows)"),
            hbox(button("Export CSV") { val f = app.timeline.export(app.dir("exports")); shareFiles(listOf(f), "SleepHub timeline") },
                button("Clear") { AlertDialog.Builder(this).setMessage("Clear the timeline?").setPositiveButton("Clear") { _, _ -> app.timeline.clear(); refresh(true) }.setNegativeButton("Cancel", null).show() }))
        box.addView(LineView(this).apply { color = Color.rgb(255, 90, 90); pts = series("ring", "heart_rate") }, LinearLayout.LayoutParams(-1, dp(70)))
        box.addView(HypnoView(this).apply { stages = app.zeo.sleep?.base ?: emptyList() }, LinearLayout.LayoutParams(-1, dp(70)))
        box.addView(text(rows.takeLast(300).reversed().joinToString("\n") { "${TimelineFmt.f(it.time)} ${it.source.padEnd(9)} ${it.key}=${it.value} ${it.note}" }, 11f).apply { typeface = Typeface.MONOSPACE })
        return scroll(box)
    }

    // ---------- Logs ----------
    private fun logsTab(): View {
        val logs = app.dir("logs")
        val box = vbox(header("Raw Bluetooth traffic"), text("Files: ${logs.absolutePath}/ring_traffic.log, zeo_traffic.log, timeline.csv", 12f, Color.LTGRAY),
            hbox(button("Share logs") { shareFiles(logs.listFiles()?.filter { it.length() > 0 }?.toList() ?: emptyList(), "SleepHub logs") },
                button("Clear logs") { TrafficLog.clear(); app.zeoLog.clear(); refresh(true) }))
        box.addView(header("Zeo"))
        box.addView(text(app.zeoLog.snapshot().takeLast(120).reversed().joinToString("\n"), 10f).apply { typeface = Typeface.MONOSPACE })
        box.addView(header("Ring"))
        box.addView(text(TrafficLog.snapshot().takeLast(120).reversed().joinToString("\n"), 10f).apply { typeface = Typeface.MONOSPACE })
        return scroll(box)
    }
}

object TimelineFmt { private val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US); @Synchronized fun f(t: Long): String = f.format(Date(t)) }
