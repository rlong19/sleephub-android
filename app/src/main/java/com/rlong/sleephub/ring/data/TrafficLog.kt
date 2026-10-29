package com.rlong.sleephub.ring.data

import com.rlong.sleephub.ring.proto.hex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer + append-only file of every BLE packet (TX/RX), for verifying the protocol. */
object TrafficLog {
    private val lines = ArrayDeque<String>()
    var file: File? = null
    val listeners = ArrayList<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Synchronized fun add(dir: String, what: String, data: ByteArray?, note: String = "") {
        val l = "${fmt.format(Date())} $dir $what" + (if (data != null) " [${data.size}] ${data.hex()}" else "") + (if (note.isNotEmpty()) "  // $note" else "")
        lines.addLast(l); while (lines.size > 2000) lines.removeFirst()
        try { file?.appendText(l + "\n") } catch (_: Exception) {}
        listeners.toList().forEach { it(l) }
    }
    @Synchronized fun snapshot(): List<String> = lines.toList()
    @Synchronized fun clear() { lines.clear(); try { file?.writeText("") } catch (_: Exception) {} }
}
