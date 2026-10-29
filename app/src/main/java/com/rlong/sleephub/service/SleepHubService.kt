package com.rlong.sleephub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.auto.AutoSpo2
import com.rlong.sleephub.ui.MainActivity
import com.rlong.sleephub.zeo.ZeoSession

/** One foreground service keeps BOTH links (ring BLE + Zeo RFCOMM) and all extra DataSources alive. */
class SleepHubService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val upd = object : Runnable { override fun run() {
        // Independent watchdogs: each device re-arms its own reconnect; neither waits on the other.
        val a = app()
        try { if (!a.demo && a.ring.state == Ring.State.DISCONNECTED && a.ring.pairedAddress != null && !a.ring.userDisconnected) a.ring.tryAutoConnect() } catch (_: Exception) {}
        try { if (!a.zeo.active) a.zeo.tryAutoConnect() } catch (_: Exception) {}
        try { a.oxi.tryAutoConnect() } catch (_: Exception) {}
        notifyNow(); h.postDelayed(this, 15_000) } }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        val n = build()
        // connectedDevice FGS type itself requires BLUETOOTH_CONNECT on API 34+: without it, don't start at all.
        if (!com.rlong.sleephub.core.BtPerms.canConnect(this)) { stopSelf(); return START_NOT_STICKY }
        try { if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n) }
        catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        val a = app()
        try { if (a.ring.state == Ring.State.DISCONNECTED) a.ring.tryAutoConnect() } catch (_: Exception) {}
        try { if (!a.zeo.active) a.zeo.tryAutoConnect() } catch (_: Exception) {}
        try { a.oxi.tryAutoConnect() } catch (_: Exception) {}
        a.extraSources.forEach { try { it.start(a.timeline) } catch (_: Exception) {} }
        h.removeCallbacks(upd); h.post(upd)
        return START_STICKY
    }
    private fun stopAll() { val a = app(); a.ring.disconnect(); a.zeo.disconnect(); a.oxi.disconnect(); a.extraSources.forEach { it.stop() }; stopForeground(true); stopSelf() }
    override fun onCreate() { super.onCreate(); instance = this; running = true }
    override fun onDestroy() { h.removeCallbacks(upd); instance = null; running = false; super.onDestroy() }
    /** Swiping the app from recents: keep the app-auto SpO2 alarm chain armed. */
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); if (AutoSpo2.enabled(this)) AutoSpo2.reschedule(this) }
    private fun notifyNow() { getSystemService(NotificationManager::class.java).notify(ID, build()) }
    private fun build(): Notification {
        val ch = "sleephub_links"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Device connections", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, SleepHubService::class.java).setAction(ACTION_STOP), flags)
        val a = app()
        val txt = "Ring: ${a.ring.state.name.lowercase()}${if (a.ring.demo) " (DEMO)" else ""} · Zeo: ${a.zeo.state.name.lowercase()}${if (a.zeo.demo) " (DEMO)" else ""}"
        val o = a.oxi; val ol = o.latest
        val big = txt + "\nOximeter: ${o.state.name.lowercase()}${o.battery?.let { " · bat $it%" } ?: ""}${if (ol != null && o.fingerOn) " · SpO2 ${ol.spo2 ?: "–"}% PR ${ol.pulse ?: "–"}" else ""}\n" + spo2Text()
        val sleepPi = PendingIntent.getBroadcast(this, 8, Intent(this, AutoSpo2.Receiver::class.java).setAction(AutoSpo2.ACTION_SLEEP_TOGGLE), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
        return b.setContentTitle("SleepHub").setContentText(txt).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi).setOngoing(true).setStyle(Notification.BigTextStyle().bigText(big))
            .addAction(Notification.Action.Builder(null, if (AutoSpo2.sleepingNow(this)) "I'm awake" else "Sleeping now", sleepPi).build())
            .addAction(Notification.Action.Builder(null, "Stop all", stop).build()).build()
    }
    private fun spo2Text(): String {
        val iv = AutoSpo2.interval(this)
        if (iv == 0) return "App auto SpO2 off"
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val last = AutoSpo2.lastReadingMs(this).takeIf { it > 0 }?.let { " · last ${AutoSpo2.lastValue(this).toInt()}% ${hm.format(java.util.Date(it))}" } ?: ""
        val next = AutoSpo2.nextMs(this).takeIf { it > 0 }?.let { " · next ${hm.format(java.util.Date(it))}" } ?: ""
        return (if (iv < 0) "Auto SpO2 continuous" else "Auto SpO2 every $iv min") + "$next$last\n${AutoSpo2.stateLine(this)}"
    }
    companion object {
        @Volatile var running = false; private set
        private var instance: SleepHubService? = null
        fun notifyRefresh(ctx: Context) { instance?.let { s -> try { s.notifyNow() } catch (_: Exception) {} } }
        /** Ring side no longer needs the FGS (v18 KeepAliveService.stop): stop only if nothing else (Zeo, a ring link) is active. */
        fun releaseRing(ctx: Context) { refresh(ctx) }
        const val ID = 77; const val ACTION_STOP = "com.rlong.sleephub.STOP"
        fun start(ctx: Context) {
            if (!com.rlong.sleephub.core.BtPerms.canConnect(ctx)) return
            val i = Intent(ctx, SleepHubService::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) {}
        }
        /** Stop the service when nothing is active any more. */
        fun refresh(ctx: Context) {
            val a = ctx.app()
            if (a.ring.state == Ring.State.DISCONNECTED && !a.zeo.active && !a.oxi.active) ctx.stopService(Intent(ctx, SleepHubService::class.java))
        }
    }
}
