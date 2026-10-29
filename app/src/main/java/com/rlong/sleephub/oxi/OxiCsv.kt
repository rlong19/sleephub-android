package com.rlong.sleephub.oxi

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Flexible parser for EMAY app CSV exports. The SDK documents "Date,Time,SpO2(%),PR(bpm)" with
 * "MM/dd/yyyy" + "h:mm:ss a"; we also accept a single combined date-time column, ISO, 24 h times, epoch s/ms,
 * ';' or tab separators and any column order (detected from the header). Wall-clock duplicates from a DST
 * fall-back are shifted forward one hour (as the SDK's DSTFoldCorrector does), so no data is overwritten.
 */
object OxiCsv {
    data class Result(val readings: List<OxiReading>, val warnings: List<String>)
    private val DATE_FMTS = listOf("MM/dd/yyyy", "M/d/yyyy", "yyyy-MM-dd", "yyyy/MM/dd", "dd.MM.yyyy", "dd/MM/yyyy")
    private val TIME_FMTS = listOf("h:mm:ss a", "hh:mm:ss a", "HH:mm:ss", "H:mm:ss", "HH:mm")

    fun parse(text: String, tz: TimeZone = TimeZone.getDefault()): Result {
        val lines = text.lineSequence().map { it.trim().trimStart('\uFEFF') }.filter { it.isNotEmpty() }.toList()
        require(lines.size > 1) { "CSV has no data rows" }
        val sep = listOf(',', ';', '\t').maxBy { c -> lines[0].count { it == c } }
        val hdr = split(lines[0], sep).map { it.lowercase(Locale.US) }
        fun col(vararg keys: String) = hdr.indexOfFirst { h -> keys.any { h.contains(it) } }
        val iSpo2 = col("spo2", "o2", "sat").takeIf { it >= 0 } ?: 2
        val iPr = col("pr", "pulse", "bpm", "hr", "heart").takeIf { it >= 0 && it != iSpo2 } ?: 3
        val iDate = hdr.indexOfFirst { it == "date" || (it.contains("date") && !it.contains("time")) }
        val iTime = hdr.indexOfFirst { it == "time" || (it.contains("time") && !it.contains("date")) }
        val iDT = hdr.indexOfFirst { it.contains("date") && it.contains("time") || it.contains("timestamp") || it.contains("epoch") }
        val (dCol, tCol) = when { iDT >= 0 -> iDT to -1; iDate >= 0 && iTime >= 0 -> iDate to iTime; iTime >= 0 -> iTime to -1; iDate >= 0 -> iDate to -1; else -> 0 to 1 }
        val out = ArrayList<OxiReading>(); val warn = ArrayList<String>()
        var prev: Long? = null; var offset = 0L
        for ((n, line) in lines.drop(1).withIndex()) {
            val f = split(line, sep)
            val ts = runCatching { parseTime(if (tCol < 0) f[dCol] else f[dCol] + " " + f[tCol], tz) }.getOrNull()
            if (ts == null) { if (warn.size < 20) warn += "Row ${n + 2}: bad time"; continue }
            if (offset > 0 && prev != null && ts >= prev) offset = 0
            var t = ts + offset
            if (prev != null && t < prev - 5_000 && t >= prev - 7_200_000) { offset += 3_600_000; t = ts + offset }
            prev = t
            fun num(i: Int) = f.getOrNull(i)?.trim()?.replace("%", "")?.toDoubleOrNull()?.toInt()
            val sp = num(iSpo2)?.takeIf { it in 1..100 }; val pr = num(iPr)?.takeIf { it in 30..250 }
            out += OxiReading(t, sp, pr)
        }
        return Result(out.sortedBy { it.time }, warn)
    }

    private fun split(l: String, sep: Char) = l.split(sep).map { it.trim().trim('"') }

    fun parseTime(s0: String, tz: TimeZone): Long? {
        val s = s0.trim()
        s.toLongOrNull()?.let { return if (it > 100_000_000_000L) it else it * 1000 }
        if (s.contains('T')) runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        val cands = ArrayList<String>()
        for (d in DATE_FMTS) for (t in TIME_FMTS) { cands += "$d $t"; cands += "${d}'T'$t" }
        for (p in cands) {
            val f = SimpleDateFormat(p, Locale.US).apply { isLenient = false; timeZone = tz }
            val pos = java.text.ParsePosition(0); val d = f.parse(s, pos)
            if (d != null && pos.index == s.length) return d.time
        }
        return null
    }
}

/** ODI3: drops ≥3 points below a rolling 120-s baseline (max-of-median-ish), lasting ≥10 s. Screening only. */
object Odi {
    data class Event(val start: Long, val end: Long, val baseline: Int, val nadir: Int)
    fun find(r: List<OxiReading>, drop: Int = 3, minMs: Long = 10_000, baseMs: Long = 120_000): List<Event> {
        val pts = r.filter { it.spo2 != null }.sortedBy { it.time }
        val ev = ArrayList<Event>(); var i = 0; var j = 0
        val win = ArrayDeque<Int>()   // indices in baseline window
        var cur: Event? = null
        while (i < pts.size) {
            val p = pts[i]
            while (j < i) { win.addLast(j); j++ }
            while (win.isNotEmpty() && pts[win.first()].time < p.time - baseMs) win.removeFirst()
            val base = if (win.size >= 10) win.map { pts[it].spo2!! }.sorted().let { it[it.size * 3 / 4] } else null
            val sp = p.spo2!!
            if (base != null && cur == null && sp <= base - drop) cur = Event(p.time, p.time, base, sp)
            else if (cur != null) {
                if (sp <= cur.baseline - drop && p.time - cur.end <= 5_000) cur = cur.copy(end = p.time, nadir = minOf(cur.nadir, sp))
                else { if (cur.end - cur.start >= minMs) ev += cur; cur = null }
            }
            i++
        }
        cur?.let { if (it.end - it.start >= minMs) ev += it }
        return ev
    }
}

/** Splits readings into sessions at gaps > 30 min. */
object OxiSessions {
    fun split(l: List<OxiReading>, gapMs: Long = 30 * 60_000L): List<List<OxiReading>> {
        val out = ArrayList<List<OxiReading>>(); var cur = ArrayList<OxiReading>()
        for (r in l) { if (cur.isNotEmpty() && r.time - cur.last().time > gapMs) { out += cur; cur = ArrayList() }; cur += r }
        if (cur.isNotEmpty()) out += cur; return out
    }
}
