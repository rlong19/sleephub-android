package com.rlong.sleephub.ring.data

import com.rlong.sleephub.ring.proto.hex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Session log for the Command probe screen. Also mirrors every line into ble_traffic.log via TrafficLog. */
class ProbeLog(private val dir: File) {
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val lineFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val file: File = File(dir, "probe_${fmt.format(Date())}.log")
    private val buf = ArrayList<String>()

    init {
        dir.mkdirs()
        file.writeText("# Da Rings command probe log — started ${Date()}\n")
    }

    @Synchronized fun line(kind: String, text: String, data: ByteArray? = null) {
        val l = "${lineFmt.format(Date())} $kind $text" +
            (if (data != null) " [${data.size}] ${data.hex()}" else "")
        buf.add(l)
        try { file.appendText(l + "\n") } catch (_: Exception) {}
        TrafficLog.add(kind, text, data, "probe")
    }

    @Synchronized fun snapshot(): List<String> = buf.toList()
}
