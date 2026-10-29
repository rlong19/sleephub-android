package com.rlong.sleephub.ui

import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import com.rlong.sleephub.R
import com.rlong.sleephub.app

@Suppress("DEPRECATION")
class SettingsActivity : PreferenceActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        addPreferencesFromResource(R.xml.prefs)
        findPreference("forget_oxi")?.setOnPreferenceClickListener { app().oxi.forget(); toast("Oximeter forgotten"); true }
        findPreference("forget_zeo")?.setOnPreferenceClickListener { app().zeo.forget(); toast("Headband forgotten"); true }
        findPreference("demo_mode")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            app().prefs.edit().putBoolean("demo_mode_user_set", true).putBoolean("demo_mode", v as Boolean).apply()
            if (v == true) toast("Demo mode ON — use Connect → Connect simulated ring. Real BLE is not used while demo is on.")
            else { app().ring.disconnect(); toast("Demo mode OFF") }
            true
        }
        findPreference("clear_demo")?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            app().db.clearDemo(); toast("Demo data cleared"); true
        }
        findPreference("stay_connected_bg")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            app().ring.setStayConnectedBg(v as Boolean)
            toast(if (v) "Background stay-connected ON" else "Background stay-connected OFF")
            true
        }
        findPreference("command_probe")?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivity(android.content.Intent(this, com.rlong.sleephub.ring.ui.ProbeActivity::class.java)); true
        }
        findPreference("auto_measure")?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivity(android.content.Intent(this, com.rlong.sleephub.ring.ui.AutoMeasureActivity::class.java)); true
        }
        findPreference("forget_ring")?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            app().ring.forgetRing(); toast("Ring forgotten"); true
        }
        val st = findPreference("app_spo2_status")
        fun status() { val a = com.rlong.sleephub.ring.auto.AutoSpo2
            st?.summary = "Last app-auto reading: " + (a.lastReadingMs(this).takeIf { it > 0 }?.let { "${a.lastValue(this).toInt()}% at ${a.fmt(it)}" } ?: "none yet") +
                "\nState: " + a.stateLine(this) + "\nNext scheduled: " + (if (a.enabled(this)) a.fmt(a.nextMs(this)) else "off") + (a.lastResult.takeIf { it.isNotEmpty() }?.let { "\nLast tick: $it" } ?: "") }
        status()
        findPreference("app_spo2_interval")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            app().prefs.edit().putString("app_spo2_interval", v as String).apply()
            com.rlong.sleephub.ring.auto.AutoSpo2.reschedule(this, fromNow = true); status()
            toast(when (v) { "0" -> "App auto SpO2 OFF"; "continuous" -> "App auto SpO2 continuous (if the ring streams)"; else -> "App auto SpO2 every $v min" }); true
        }
        findPreference("sleeping_now")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            com.rlong.sleephub.ring.auto.AutoSpo2.setSleepingNow(this, v as Boolean); status(); true }
        findPreference("app_spo2_when")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            app().prefs.edit().putString("app_spo2_when", v as String).apply(); com.rlong.sleephub.ring.auto.AutoSpo2.tick(this, manual = true); status(); true }
        for (k in listOf("spo2_win_start", "spo2_win_end")) findPreference(k)?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            (com.rlong.sleephub.ring.auto.SpO2When.minutes(v as String) != null).also { if (!it) toast("Use HH:mm, e.g. 22:00") } }
        findPreference("app_spo2_battery")?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }; true
        }
        // Reflect current stay_connected value into the checkbox (Ring owns the key)
        findPreference("stay_connected_bg")?.let {
            (it as? android.preference.CheckBoxPreference)?.isChecked = app().ring.stayConnectedBg()
        }
    }
}
