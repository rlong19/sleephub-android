package com.rlong.sleephub

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import com.rlong.sleephub.core.DataSource
import com.rlong.sleephub.core.Timeline
import com.rlong.sleephub.core.TrafficLogger
import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.data.RingDb
import com.rlong.sleephub.ring.data.TrafficLog
import com.rlong.sleephub.serial.SleepManagerSource
import com.rlong.sleephub.service.SleepHubService
import com.rlong.sleephub.zeo.ZeoSession
import java.io.File

class App : Application() {
    companion object { lateinit var I: App; private set; const val VERSION = "0.3.11" }
    val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    val timeline = Timeline()
    val zeoLog = TrafficLogger("zeo")
    val db by lazy { RingDb(this) }
    val ring by lazy { Ring(this, db) }
    val zeoDb by lazy { com.rlong.sleephub.zeo.ZeoDb(this) }
    val zeo by lazy { ZeoSession(this, zeoLog, timeline) }
    val oxiLog = TrafficLogger("oximeter")
    val oxiDb by lazy { com.rlong.sleephub.oxi.OxiDb(this) }
    val oxi by lazy { com.rlong.sleephub.oxi.Oximeter(this, prefs, oxiDb, oxiLog, timeline) }
    /** Extra streams (hook). The Sleep Manager serial source is a placeholder, not started. */
    val extraSources: MutableList<DataSource> = mutableListOf()
    val sleepManagerPlaceholder = SleepManagerSource()
    /** DEMO (simulated Zeo) — OFF by default, must be switched on in Settings. */
    val zeoDemoEnabled get() = prefs.getBoolean("zeo_demo", false)
    val ringDemoEnabled get() = prefs.getBoolean("demo_mode", false)
    // v18 ring-side API (ring/ code uses these names)
    val demoUserSet get() = prefs.getBoolean("demo_mode_user_set", false)
    val demo get() = demoUserSet && prefs.getBoolean("demo_mode", false)
    val showingDemo get() = demo && ring.demo
    private var started = 0
    /** True while any activity is visible (auto full sync runs only in the foreground). */
    val foreground get() = started > 0
    fun dir(name: String) = File(getExternalFilesDir(null) ?: filesDir, name).apply { mkdirs() }
    override fun onCreate() {
        super.onCreate(); I = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { started++; if (started == 1 && ring.state == Ring.State.CONNECTED) ring.maybeAutoSync("app opened") }
            override fun onActivityStopped(a: android.app.Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        PreferenceManager.setDefaultValues(this, R.xml.prefs, false)
        val logs = dir("logs")
        TrafficLog.file = File(logs, "ring_traffic.log")
        zeoLog.file = File(logs, "zeo_traffic.log")
        timeline.file = File(logs, "timeline.csv")
        oxiLog.file = File(logs, "oximeter_traffic.log")
        ring.onLive = { m, v, demo -> timeline.add(if (demo) "ring-DEMO" else "ring", m.name.lowercase(), if (v % 1.0 == 0.0) v.toLong() else "%.2f".format(v)) }
        // Repair/re-map stored Zeo nights from raw headband seconds with the saved per-headband clock offset (idempotent).
        zeo.savedAddress?.let { mac -> val k = "zeo_clock_offset_$mac"; if (prefs.contains(k)) { val o = prefs.getLong(k, 0L); Thread { try { zeoDb.remap(o) } catch (_: Exception) {} }.start() } }
        startLinks()
    }
    /** Auto-connect all saved devices + FGS. Called at startup and again from MainActivity once BT permissions are granted. */
    fun startLinks() {
        if (!com.rlong.sleephub.core.BtPerms.canConnect(this)) return
        if (!demo) {
            try { ring.tryAutoConnect() } catch (_: Exception) {}
            if (com.rlong.sleephub.ring.auto.AutoSpo2.enabled(this)) com.rlong.sleephub.ring.auto.AutoSpo2.reschedule(this)
        }
        try { zeo.tryAutoConnect() } catch (_: Exception) {}
        try { oxi.tryAutoConnect() } catch (_: Exception) {}
        if (oxi.savedAddress != null || ring.pairedAddress != null || zeo.savedAddress != null && prefs.getBoolean("zeo_enabled", false)) SleepHubService.start(this)
    }
}
fun Context.app() = applicationContext as App
