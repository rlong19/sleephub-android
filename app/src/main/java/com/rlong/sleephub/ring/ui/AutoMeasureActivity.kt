package com.rlong.sleephub.ring.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.proto.AutoMeasure

/** The only place the app writes 1/06, 1/07, 1/08, 1/27 (they stay blocked in the probe's custom box). */
class AutoMeasureActivity : Activity() {
    private val ring get() = app().ring
    private lateinit var root: LinearLayout
    private val listener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = vbox(); setContentView(scroll(root))
    }
    override fun onResume() { super.onResume(); ring.listeners += listener; if (ring.state == Ring.State.CONNECTED) ring.queryAutoMeasure(); render() }
    override fun onPause() { super.onPause(); ring.listeners -= listener }

    private fun render() {
        root.removeAllViews()
        root.addView(text("Ring auto-measure", 20f, Color.CYAN))
        root.addView(text(if (ring.state == Ring.State.CONNECTED) "Connected — values below are read from the ring." else "Not connected — showing last values read. Connect to change.", 13f,
            if (ring.state == Ring.State.CONNECTED) Color.GREEN else Color.YELLOW))
        root.addView(text("SpO2 capability (2/25): ${AutoMeasure.spo2TypeLabel(ring.spo2Type)}", 14f))
        for (m in AutoMeasure.METRICS) {
            val cur = ring.intervals[AutoMeasure.queryCmd(m)]
            root.addView(hbox(
                text("${m.label}: ${AutoMeasure.label(cur)}", 16f),
                button("Set ${m.label}…") {
                    if (ring.state != Ring.State.CONNECTED) { toast("Not connected"); return@button }
                    val opts = AutoMeasure.ALLOWED_MINUTES
                    AlertDialog.Builder(this).setTitle("${m.label} interval")
                        .setItems(opts.map { if (it == 0) "Off" else "$it min" }.toTypedArray()) { _, i -> ring.writeAutoMeasure(m, opts[i]) }.show()
                },
            ))
        }
        if (ring.autoMeasureMsg.isNotEmpty()) root.addView(text(ring.autoMeasureMsg, 14f, Color.rgb(180, 255, 180)))
        root.addView(button("Re-read from ring") { if (ring.state == Ring.State.CONNECTED) ring.queryAutoMeasure() else toast("Not connected") })
        root.addView(text("Battery: every automatic optical measurement lights the PPG LEDs. Shorter intervals (5–15 min), " +
            "especially for SpO2 (red/IR LEDs), noticeably shorten battery life. The stock app only offers SpO2 hourly (60 min) or off.", 12f, Color.LTGRAY))
        root.addView(text("Encoding (SDK b1.t/u/h/h0): group 1 cmd 06 HR / 07 HRV / 08 SpO2 / 27 stress, payload = minutes ÷ 5, 00 = off. " +
            "Read back with 2/06, 2/07, 2/08, 2/2D. If the ring reports SpO2 'sleep only', it only measures SpO2 while you are asleep.", 11f, Color.GRAY))
    }
}
