package com.rlong.sleephub.ring.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.rlong.sleephub.App
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.data.ProbeLog
import com.rlong.sleephub.ring.proto.Frame
import com.rlong.sleephub.ring.proto.ProbeCatalog
import com.rlong.sleephub.ring.proto.ProbeClass
import com.rlong.sleephub.ring.proto.ProbeCmd
import com.rlong.sleephub.ring.proto.hex
import com.rlong.sleephub.ring.proto.hexBytes
import java.io.File

/**
 * Settings → Command probe. Sends SAFE-READ / SAFE-TOGGLE CRP commands only.
 * DANGEROUS (group,cmd) pairs are never listed and are refused in the custom box.
 */
class ProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var log: ProbeLog
    private lateinit var status: TextView
    private lateinit var replyView: TextView
    private lateinit var logView: TextView
    private lateinit var listBox: LinearLayout
    private var lastSent: ProbeCmd? = null
    private var activeToggle: ProbeCmd? = null
    private var stopBtn: View? = null
    private var runningAll = false
    private var allQueue: ArrayDeque<ProbeCmd> = ArrayDeque()
    private val autoStop = Runnable { stopActive("auto-stop 30s") }
    private val replyListener: (ByteArray) -> Unit = { frame -> onReply(frame) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "Command probe"
        log = ProbeLog(app().dir("logs"))
        log.line("SYS", "probe open v=${App.VERSION} connected=${app().ring.state} mac=${app().ring.pairedAddress}")

        status = text("—", 14f, Color.YELLOW)
        replyView = text("No reply yet", 13f, Color.CYAN).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        logView = text("", 11f, Color.LTGRAY).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val customG = EditText(this).apply {
            hint = "group (dec)"; inputType = InputType.TYPE_CLASS_NUMBER; setText("2")
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val customC = EditText(this).apply {
            hint = "cmd (dec)"; inputType = InputType.TYPE_CLASS_NUMBER; setText("19")
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val customP = EditText(this).apply {
            hint = "payload hex (e.g. 00 00)"; setText("00 00")
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }

        val root = vbox(
            text("SAFE commands only. DANGEROUS pairs are hidden and blocked.", 13f, Color.rgb(255, 180, 80)),
            text("Actigraphy: 2/19 = 1-min sleep movement; 2/14 = hypnogram; 2/18 = 30-min steps.", 12f, Color.rgb(180, 220, 255)),
            status,
            hbox(
                button("Run all SAFE-READ") { runAllSafeRead() },
                button("Stop toggle") { stopActive("user stop") },
                button("Export log") { exportLog() },
            ),
            text("Last reply", 14f, Color.WHITE),
            replyView,
            text("Custom (blocked if DANGEROUS)", 14f, Color.WHITE),
            hbox(customG, customC),
            customP,
            button("Send custom") {
                val g = customG.text.toString().toIntOrNull()
                val c = customC.text.toString().toIntOrNull()
                if (g == null || c == null) { toast("group/cmd required"); return@button }
                if (ProbeCatalog.isBlocked(g, c)) {
                    toast("Refused: $g/$c is DANGEROUS")
                    log.line("SYS", "refused DANGEROUS g=$g c=$c")
                    appendLog("REFUSED dangerous g=$g c=$c")
                    return@button
                }
                val payload = try {
                    val s = customP.text.toString().trim()
                    if (s.isEmpty()) ByteArray(0) else hexBytes(s)
                } catch (e: Exception) { toast("bad hex"); return@button }
                val fake = ProbeCmd("custom", g, c, payload, ProbeClass.SAFE_READ, "custom", "custom")
                sendCmd(fake)
            },
            button("Fetch today actigraphy pages 0–7") { fetchActigraphyDay(0) },
            text("Catalog (${ProbeCatalog.ALL.size} safe)", 14f, Color.WHITE),
            listBox,
            text("Probe log", 14f, Color.WHITE),
            logView,
        )
        // Build catalog rows: actigraphy/sleep first, then toggles, then other reads
        val priority = listOf("queryActionDetails", "queryHistorySleep", "queryHistoryStepsDetails",
            "queryHistorySleepList", "querySleepDetails", "querySleepInfo", "querySleepChronotype",
            "querySleepState", "queryFullDayPhysicalStrength", "enableRecordingStream", "startRecording",
            "queryTestNotWornData", "startTest", "querySupportTestList", "queryHardwareCheckResult")
        val ordered = ProbeCatalog.ALL.sortedWith(compareBy(
            { cmd -> priority.indexOf(cmd.title).let { if (it < 0) 1000 else it } },
            { it.group }, { it.cmd }, { it.title }
        ))
        for (cmd in ordered) addRow(cmd)

        setContentView(scroll(root).apply { setBackgroundColor(Color.rgb(20, 20, 24)) })
        refreshStatus()
    }

    private fun addRow(cmd: ProbeCmd) {
        val tag = when {
            "actigraphy" == cmd.decode || cmd.title.contains("Action") -> " [ACTIGRAPHY 1-min]"
            cmd.decode.startsWith("sleep") -> " [SLEEP]"
            cmd.rawStream -> " [STREAM 30s]"
            cmd.cls == ProbeClass.SAFE_TOGGLE -> " [TOGGLE]"
            "self-test" in cmd.tags -> " [SELF-TEST]"
            else -> ""
        }
        val row = hbox(
            text("${cmd.group}/${"%02X".format(cmd.cmd)} ${cmd.title}$tag\n${cmd.summary}", 12f, Color.WHITE),
            button(if (cmd.cls == ProbeClass.SAFE_TOGGLE) "Start" else "Send") { sendCmd(cmd) },
        )
        row.setPadding(0, dp(4), 0, dp(4))
        listBox.addView(row)
    }

    private fun refreshStatus() {
        val r = app().ring
        status.text = "Ring: ${r.state} ${r.pairedName ?: ""} ${r.pairedAddress ?: ""}  bat=${r.battery?.label ?: "?"}  fw=${r.firmware}"
    }

    private fun sendCmd(cmd: ProbeCmd) {
        val ring = app().ring
        if (ring.state != Ring.State.CONNECTED) {
            toast("Not connected — connect the ring first")
            log.line("SYS", "not connected; skip ${cmd.title}")
            return
        }
        if (ProbeCatalog.isBlocked(cmd.group, cmd.cmd)) {
            toast("Blocked DANGEROUS ${cmd.group}/${cmd.cmd}")
            return
        }
        // Stop previous toggle if starting another
        if (cmd.cls == ProbeClass.SAFE_TOGGLE) {
            stopActive("switching")
            activeToggle = cmd
            main.removeCallbacks(autoStop)
            main.postDelayed(autoStop, 30_000)
        }
        lastSent = cmd
        val frame = cmd.frame()
        log.line("TX", "${cmd.title} ${cmd.cls}", frame)
        appendLog("TX ${cmd.title} ${frame.hex()}")
        ring.send(frame, "probe:${cmd.title}")
        replyView.text = "Sent ${cmd.group}/${"%02X".format(cmd.cmd)} — waiting…"
        refreshStatus()
    }

    private fun stopActive(why: String) {
        main.removeCallbacks(autoStop)
        val t = activeToggle ?: return
        activeToggle = null
        val stop = t.stopFrame() ?: return
        log.line("TX", "STOP ${t.title} ($why)", stop)
        appendLog("TX STOP ${t.title} ($why)")
        app().ring.send(stop, "probe:stop:$why")
    }

    private fun onReply(frame: ByteArray) {
        val decoded = ProbeCatalog.decodeReply(lastSent, frame)
        val hex = frame.hex()
        log.line("RX", decoded, frame)
        runOnUiThread {
            replyView.text = "$decoded\n\n$hex"
            appendLog("RX $decoded")
            // Auto-page actigraphy / phys strength when we see page replies
            maybeAutoPage(frame)
            if (runningAll) scheduleNext()
        }
    }

    private fun maybeAutoPage(frame: ByteArray) {
        if (frame.size < 8) return
        val g = frame[4].toInt() and 0xFF
        val c = frame[5].toInt() and 0xFF
        val day = frame[6].toInt() and 0xFF
        val page = frame[7].toInt() and 0xFF
        // Actigraphy pages 0..7; physical strength pages 0..3
        when {
            g == 2 && c == 19 && page < 7 -> {
                val next = ProbeCmd("actigraphy_p", 2, 19, byteArrayOf(day.toByte(), (page + 1).toByte()),
                    ProbeClass.SAFE_READ, "queryActionDetails", "page ${page + 1}", decode = "actigraphy")
                main.postDelayed({ sendCmd(next) }, 400)
            }
            g == 7 && c == 14 && page < 3 -> {
                val next = ProbeCmd("phys_p", 7, 14, byteArrayOf(day.toByte(), (page + 1).toByte()),
                    ProbeClass.SAFE_READ, "queryFullDayPhysicalStrength", "page ${page + 1}", decode = "phys5")
                main.postDelayed({ sendCmd(next) }, 400)
            }
        }
    }

    private fun fetchActigraphyDay(day: Int) {
        // Kick page 0; maybeAutoPage will follow 1..7
        val cmd = ProbeCmd("actigraphy_day", 2, 19, byteArrayOf(day.toByte(), 0),
            ProbeClass.SAFE_READ, "queryActionDetails", "full day actigraphy", decode = "actigraphy")
        sendCmd(cmd)
    }

    private val runAllTick = object : Runnable {
        override fun run() {
            if (!runningAll) return
            val next = allQueue.removeFirstOrNull()
            if (next == null) {
                runningAll = false
                appendLog("=== Run all done ===")
                toast("SAFE-READ sweep done")
                return
            }
            sendCmd(next)
            main.postDelayed(this, 1500)
        }
    }

    private fun runAllSafeRead() {
        if (runningAll) { toast("Already running"); return }
        allQueue = ArrayDeque(ProbeCatalog.SAFE_READ)
        runningAll = true
        log.line("SYS", "run-all SAFE-READ n=${allQueue.size}")
        appendLog("=== Run all SAFE-READ (${allQueue.size}) ===")
        main.removeCallbacks(runAllTick)
        main.post(runAllTick)
    }

    private fun scheduleNext() { /* replies don't drive the sweep; timer does */ }

    private fun exportLog() {
        shareFiles(listOf(log.file), "Da Rings probe log")
    }

    private fun appendLog(s: String) {
        logView.append(s + "\n")
        // keep view from growing forever in UI
        val t = logView.text
        if (t.length > 20_000) logView.text = t.substring(t.length - 16_000)
    }

    override fun onResume() {
        super.onResume()
        app().ring.probeReplyListeners.add(replyListener)
        refreshStatus()
    }

    override fun onPause() {
        app().ring.probeReplyListeners.remove(replyListener)
        stopActive("leave screen")
        runningAll = false
        main.removeCallbacks(runAllTick)
        super.onPause()
    }
}
