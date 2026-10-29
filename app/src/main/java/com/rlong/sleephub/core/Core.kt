package com.rlong.sleephub.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ByteArray.hexs(): String = joinToString(" ") { "%02X".format(it) }

/** Append-only raw traffic log (one file per device) + in-memory tail for the UI. */
class TrafficLogger(val name: String) {
    private val lines = ArrayDeque<String>()
    @Volatile var file: File? = null
    val listeners = ArrayList<(String) -> Unit>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Synchronized fun add(dir: String, what: String, data: ByteArray?, note: String = "") {
        val l = "${fmt.format(Date())} $dir $what" + (if (data != null) " [${data.size}] ${data.hexs()}" else "") + (if (note.isNotEmpty()) "  // $note" else "")
        lines.addLast(l); while (lines.size > 1500) lines.removeFirst()
        try { file?.appendText(l + "\n") } catch (_: Exception) {}
        listeners.toList().forEach { try { it(l) } catch (_: Exception) {} }
    }
    @Synchronized fun snapshot(): List<String> = lines.toList()
    @Synchronized fun clear() { lines.clear(); try { file?.writeText("") } catch (_: Exception) {} }
}

/** One row of the combined timeline. source = ring | zeo | zeo-DEMO | ring-DEMO | sleepmanager … */
data class TimelineRow(val time: Long, val source: String, val key: String, val value: String, val note: String = "") {
    fun csv(): String = listOf(iso(time), time.toString(), source, key, value, note).joinToString(",") { q(it) }
    companion object {
        const val HEADER = "time_local,epoch_ms,source,key,value,note"
        private val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        @Synchronized fun iso(t: Long): String = f.format(Date(t))
        fun q(s: String) = if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

/** Combined, time-ordered timeline for all sources. Persisted continuously to files/logs/timeline.csv. */
class Timeline {
    private val rows = ArrayList<TimelineRow>()
    @Volatile var file: File? = null
    val listeners = ArrayList<() -> Unit>()
    @Synchronized fun add(r: TimelineRow) {
        rows += r; if (rows.size > 50_000) rows.subList(0, 10_000).clear()
        try { file?.let { if (!it.exists() || it.length() == 0L) it.writeText(TimelineRow.HEADER + "\n"); it.appendText(r.csv() + "\n") } } catch (_: Exception) {}
        listeners.toList().forEach { try { it() } catch (_: Exception) {} }
    }
    fun add(source: String, key: String, value: Any, note: String = "", time: Long = System.currentTimeMillis()) = add(TimelineRow(time, source, key, value.toString(), note))
    @Synchronized fun snapshot(): List<TimelineRow> = rows.sortedBy { it.time }
    @Synchronized fun clear() { rows.clear(); try { file?.writeText(TimelineRow.HEADER + "\n") } catch (_: Exception) {} }
    fun toCsv(list: List<TimelineRow> = snapshot()): String = buildString { append(TimelineRow.HEADER).append('\n'); list.forEach { append(it.csv()).append('\n') } }
    fun export(dir: File): File { val f = File(dir, "sleephub-timeline-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"); f.writeText(toCsv()); return f }
}

/**
 * Hook for additional data streams (e.g. the bedside Sleep Manager's 38400-baud A4 serial stream).
 * Implementations push rows into the [Timeline] and raw bytes into their own [TrafficLogger].
 * Register in App.extraSources; the foreground service starts/stops them with the others.
 */
interface DataSource {
    val id: String
    val label: String
    val status: String
    fun start(timeline: Timeline)
    fun stop()
}
