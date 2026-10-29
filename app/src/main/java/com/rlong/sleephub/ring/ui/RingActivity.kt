package com.rlong.sleephub.ring.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.rlong.sleephub.App
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.ble.FoundRing
import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.data.TrafficLog
import com.rlong.sleephub.ring.proto.Metric
import com.rlong.sleephub.ring.proto.Cmd
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Full v18 ring screens (Connect … History … Debug TX), hosted inside SleepHub. */
class RingActivity : Activity() {
    private val app get() = app()
    private val ring get() = app.ring
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var tab = 0
    private val tabs = listOf("Connect", "Dashboard", "Heart", "SpO2", "HRV", "Stress", "Temp", "Steps", "History", "Raw log", "Debug TX")
    private val listener: () -> Unit = { runOnUiThread { refresh(fromRing = true) } }
    private val found = ConcurrentHashMap<String, FoundRing>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        banner = demoBanner()
        status = text("", 13f)
        tabBar = LinearLayout(this)
        content = FrameLayout(this)
        val root = vbox(banner, status, HorizontalScrollView(this).apply { addView(tabBar) })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ensureBlePerms()
        if (!app.demo) ring.tryAutoConnect()
    }
    override fun onResume() { super.onResume(); ring.listeners += listener; HistoryCache.invalidate(); refresh() }
    override fun onPause() { super.onPause(); ring.listeners -= listener; ring.stopScan() }

    private fun ensureBlePerms() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.BLUETOOTH_CONNECT
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.ACCESS_FINE_LOCATION; need += Manifest.permission.ACCESS_COARSE_LOCATION
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private var lastHistoryBuild = 0L
    private var wasSyncing = false; private var histPending = false
    private fun statusLine(): String {
        val meas = ring.measuring?.let { "\n⏱ ${it.label}: ${ring.measureRemainingSec()}s left — ${ring.measureMessage}" }
            ?: ring.measureMessage.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        return "${ring.status}  |  ${ring.deviceName.ifEmpty { ring.pairedName ?: "—" }}  fw=${ring.firmware.ifEmpty { "?" }}  bat=${ring.battery?.label ?: "?"}" +
            meas + if (app.showingDemo) "\n[DEMO]" else ""
    }
    private val deferredHistory = Runnable { refresh(fromRing = true) }
    private fun refresh(fromRing: Boolean = false) {
        // History: ring events must not rebuild the page under a finger, and rebuild at most every 3 s keeping scroll
        // per-metric screens: never rebuild under a scrubbing finger
        if (fromRing && tab in 2..6 && Scrub.active) { handler.removeCallbacks(deferredHistory); handler.postDelayed(deferredHistory, 1000); return }
        // v0.3.12: History ignores ring events (no periodic rebuild); it reloads on Refresh, a finished sync, screen open, or a day change.
        val syncing = ring.fullSyncRunning || app.oxi.syncing
        if (wasSyncing && !syncing) histPending = true; wasSyncing = syncing
        if (fromRing && tab == 8) {
            if (Scrub.active || !(histPending || HistoryCache.dayChanged())) { status.text = statusLine(); return }
            HistoryCache.invalidate()
        }
        val keepY = if (tab == 8) (content.getChildAt(0) as? android.widget.ScrollView)?.scrollY ?: 0 else 0
        if (tab == 8) { histPending = false; lastHistoryBuild = android.os.SystemClock.uptimeMillis() }
        banner.visibility = if (app.showingDemo) View.VISIBLE else View.GONE
        status.text = statusLine()
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, n -> tabBar.addView(button(n) { tab = i; if (i == 8) HistoryCache.invalidate(); refresh() }.apply { setTextColor(if (i == tab) Color.CYAN else Color.WHITE) }) }
        content.removeAllViews()
        content.addView(when (tab) {
            0 -> connectTab(); 1 -> dashTab(); 2 -> metricTab(Metric.HEART_RATE, Color.RED)
            3 -> metricTab(Metric.SPO2, Color.rgb(0, 150, 255)); 4 -> metricTab(Metric.HRV, Color.GREEN)
            5 -> metricTab(Metric.STRESS, Color.rgb(230, 149, 0)); 6 -> metricTab(Metric.TEMP, Color.MAGENTA)
            7 -> stepsTab(); 8 -> historyTab(); 9 -> rawTab(); else -> debugTxTab()
        })
        if (tab == 8 && fromRing && keepY > 0) (content.getChildAt(0) as? android.widget.ScrollView)?.let { sv -> sv.post { sv.scrollTo(0, keepY) } }
    }

    private fun connectTab(): View {
        val list = ListView(this)
        val items = ArrayList(found.values.sortedByDescending { it.rssi })
        list.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = items[p]
                return text("${r.name}\n${r.address}  rssi=${r.rssi}  ${r.info}", 14f)
            }
        }
        list.setOnItemClickListener { _, _, p, _ ->
            ring.stopScan(); ring.connect(items[p].address, items[p].name, autoConnect = false)
            toast("Connecting… Force-stop the original Da Rings app first if the ring is already bonded to it.")
        }
        val paired = ring.pairedAddress
        val head = vbox(
            text("Scan finds MoYoung rings advertising service data on FDDA. The ring usually allows only one active GATT client — force-stop com.moyoung.ring while testing, but you do NOT need to unpair.", 12f, Color.LTGRAY),
            text(if (paired != null) "Saved ring: ${ring.pairedName ?: "?"}  $paired" else "No saved ring — tap a scan result to pair.", 13f, Color.CYAN),
            hbox(
                button("Scan") {
                    found.clear(); app.oxi.stopScan() // one BLE scan at a time: oximeter scan stopped so it cannot starve/throttle the ring scan
                    ring.scan { r -> found[r.address] = r; handler.post { (list.adapter as BaseAdapter).notifyDataSetChanged(); items.clear(); items += found.values.sortedByDescending { it.rssi } } }?.let { toast(it) }
                },
                button("Stop") { ring.stopScan() },
                button("Disconnect") { ring.disconnect() },
            ),
            hbox(
                button("Reconnect") { if (paired == null) toast("No saved ring") else ring.tryAutoConnect() },
                button("Forget ring") { ring.forgetRing(); toast("Forgotten") },
            ),
            syncButtons(ring),
            hbox(
                button("Find ring") { ring.findRing(true); handler.postDelayed({ ring.findRing(false) }, 3000) },
            ),
            hbox(
                button("Command probe (testing)") { startActivity(Intent(this, ProbeActivity::class.java)) },
                button("Auto-measure") { startActivity(Intent(this, AutoMeasureActivity::class.java)) },
            ),
        )
        if (app.demo) head.addView(button("Connect simulated ring") { ring.connectDemo() })
        val root = vbox(head); root.addView(list, LinearLayout.LayoutParams(-1, -1)); return root
    }

    private fun dashTab(): View {
        val col = vbox(text("Live readings", 18f, Color.CYAN))
        for (m in listOf(Metric.HEART_RATE, Metric.SPO2, Metric.HRV, Metric.STRESS, Metric.TEMP, Metric.STEPS)) {
            val v = ring.live[m] ?: app.db.latest(m, app.showingDemo)?.value
            col.addView(text("${m.label}: ${v?.let { String.format(Locale.US, "%.1f %s", it, m.unit) } ?: "—"}", 16f))
        }
        col.addView(button("History") { tab = 8; HistoryCache.invalidate(); refresh() })
        col.addView(text("\nMeasure now (stops on result, Cancel, or timeout — SpO2/HRV/Stress up to 90s):", 14f, Color.CYAN))
        if (ring.measuring != null) {
            col.addView(text("⏱ ${ring.measuring!!.label}: ${ring.measureRemainingSec()}s — ${ring.measureMessage}", 15f, Color.YELLOW))
            col.addView(button("Cancel measure") { ring.cancelMeasure("user cancel") })
        }
        for (m in listOf(Metric.HEART_RATE, Metric.SPO2, Metric.HRV, Metric.STRESS, Metric.TEMP)) {
            col.addView(hbox(
                button("Start ${m.label}") { ring.startMeasure(m) },
                button("Cancel") { if (ring.measuring == m) ring.cancelMeasure("user cancel") else toast("Not measuring ${m.label}") },
            ))
        }
        return scroll(col)
    }

    private fun metricTab(m: Metric, color: Int): View {
        val dayAgo = System.currentTimeMillis() - 2 * 86400_000L
        val samples = app.db.samples(m, dayAgo, demo = app.showingDemo)
        // pre-v11 sequential chart (by index, no time axis) + the shared History scrub gesture/popup
        val scrub = Scrub()
        val chart = scrubFrame(ChartView(this, scrub).apply { this.color = color; this.samples = samples }, scrub) { _, i ->
            samples.getOrNull(i.toInt())?.let { samplePopupText(m, it) } ?: "—" }
        val latest = samples.lastOrNull()
        val head = text("${m.label}: ${latest?.let { String.format(Locale.US, "%.1f %s @ %s", it.value, m.unit, SimpleDateFormat("HH:mm", Locale.US).format(Date(it.time))) } ?: "no data"} (${samples.size} pts)", 14f)
        val root = vbox(head)
        if (m == Metric.SPO2) com.rlong.sleephub.ring.proto.Support.spo2(ring.spo2Type)?.let { root.addView(text(it, 12f, Color.YELLOW)) }
        if (m == Metric.SPO2) {
            val a = com.rlong.sleephub.ring.auto.AutoSpo2
            root.addView(text(a.stateLine(this), 12f, Color.rgb(180, 220, 255)))
            root.addView(button(if (a.sleepingNow(this)) "I'm awake (stop sleep SpO2)" else "Sleeping now (start sleep SpO2)") { a.setSleepingNow(this, !a.sleepingNow(this)); refresh() })
        }
        root.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        if (ring.measuring == m) {
            root.addView(text("⏱ ${ring.measureRemainingSec()}s left — ${ring.measureMessage}", 14f, Color.YELLOW))
            root.addView(button("Cancel") { ring.cancelMeasure("user cancel") })
        } else {
            root.addView(button("Measure now") { ring.startMeasure(m) })
            root.addView(button("History") { tab = 8; HistoryCache.invalidate(); refresh() })
        }
        return root
    }

    private fun stepsTab(): View {
        ring.readSteps()
        val days = app.db.stepsDays(app.showingDemo)
        val live = ring.live[Metric.STEPS]
        val col = vbox(text("Current steps: ${live?.toInt() ?: "—"}", 16f, Color.CYAN))
        days.forEach { d -> col.addView(text(SimpleDateFormat("EEE MMM d", Locale.US).format(Date(d[0] * 1000L)) +
            "  steps=${d[1]}  dist=${d[2]}m  kcal=${d[3]}", 13f)) }
        val today = Calendarish.todayStart()
        val half = app.db.steps30(today, today + 86400_000L, app.showingDemo)
        if (half.isNotEmpty()) {
            col.addView(text("Today per 30 min:", 14f, Color.CYAN))
            col.addView(ChartView(this).apply {
                color = Color.YELLOW
                samples = half.map { com.rlong.sleephub.ring.proto.Sample(Metric.STEPS, it.first, it.second.toDouble(), "30m") }
            }, LinearLayout.LayoutParams(-1, dp(160)))
        }
        return scroll(col)
    }

    private var historyKey: Long? = null
    private fun historyTab(): View {
        val mode = runCatching { com.rlong.sleephub.ring.data.WindowMode.valueOf(app.prefs.getString("history_window", "NOON")!!) }.getOrDefault(com.rlong.sleephub.ring.data.WindowMode.NOON)
        return historyView(historyKey, mode, { historyKey = it; refresh() }, { app.prefs.edit().putString("history_window", it.name).apply(); refresh() }) { refresh() }
    }

    private fun rawTab(): View {
        val pathHint = text("Auto-saves to ${TrafficLog.file?.absolutePath ?: "(no file yet)"}", 11f, Color.LTGRAY)
        val log = text(TrafficLog.snapshot().takeLast(300).joinToString("\n"), 11f, Color.rgb(180, 255, 180))
        log.typeface = android.graphics.Typeface.MONOSPACE
        val listener: (String) -> Unit = { handler.post { log.text = TrafficLog.snapshot().takeLast(300).joinToString("\n") } }
        TrafficLog.listeners += listener
        log.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { TrafficLog.listeners -= listener }
        })
        return vbox(
            pathHint,
            hbox(
                button("Clear") { TrafficLog.clear(); log.text = "" },
                button("Copy") {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("ble_traffic", TrafficLog.snapshot().joinToString("\n")))
                    toast("Copied ${TrafficLog.snapshot().size} lines")
                },
                button("Share log") { shareFiles(listOfNotNull(TrafficLog.file), "BLE traffic log") },
            ),
            scroll(log),
        )
    }

    /** Labelled measure-start variants — use if stock Start HR/SpO2 still does nothing after WRITE_NO_RESPONSE fix. */
    private fun debugTxTab(): View {
        val col = vbox(
            text("Debug measure variants", 18f, Color.CYAN),
            text("Real session: stock bytes FD DA 10 07 01 09 01 were correct but every write returned status=3 (WRITE_NOT_PERMITTED) because FDD2 was written with WRITE_TYPE_DEFAULT. Default Start buttons now use WRITE_NO_RESPONSE. Use these only if LED still stays off.", 12f, Color.LTGRAY),
        )
        val metrics = listOf(Metric.HEART_RATE, Metric.SPO2, Metric.HRV)
        for (m in metrics) {
            col.addView(text(m.label, 14f, Color.CYAN))
            for (v in com.rlong.sleephub.ring.proto.Cmd.MeasureVariant.values()) {
                col.addView(button("${m.label}: ${v.label}") {
                    if (ring.state != Ring.State.CONNECTED) toast("Not connected")
                    else { ring.tryMeasureVariant(m, v); toast("Sent ${v.name}") }
                })
            }
        }
        col.addView(button("Stop all measures") {
            for (m in metrics) ring.measure(m, false)
        })
        return scroll(col)
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        listOf("Settings", "Export + share", "About").forEachIndexed { i, s -> m.add(0, i, i, s) }
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            0 -> startActivity(Intent(this, com.rlong.sleephub.ui.SettingsActivity::class.java))
            1 -> {
                val dir = app.dir("exports")
                Thread {
                    val files = app.db.export(this, dir)
                    runOnUiThread { shareFiles(files, "SleepHub ring export") }
                }.start()
            }
            2 -> AlertDialog.Builder(this).setTitle("About")
                .setMessage("SleepHub ${App.VERSION} — ring side ported from Da Rings recreated 1.3.5-recreated.18\nMoYoung / CRP BLE protocol, local-only.\nNo cloud login. Demo mode defaults OFF.")
                .setPositiveButton("OK", null).show()
        }
        return true
    }
}

object Calendarish {
    fun todayStart(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
