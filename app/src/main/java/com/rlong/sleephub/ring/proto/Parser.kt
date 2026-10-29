package com.rlong.sleephub.ring.proto

import java.util.Calendar
import java.util.TimeZone

enum class Metric(val label: String, val unit: String) {
    HEART_RATE("Heart rate", "bpm"), SPO2("SpO2", "%"), HRV("HRV", "ms"), STRESS("Stress", ""), TEMP("Temperature", "°C"),
    STEPS("Steps", "steps"), BATTERY("Battery", "%"), BP_SYS("BP systolic", "mmHg"), BP_DIA("BP diastolic", "mmHg"),
    /** 7/0E full-day / 7/0C current "physical strength" (likely Gomore energy reserve; meaning inferred). u16 LE / 10. */
    ENERGY("Energy (physical strength)", "")
}

/**
 * Plausibility rules for ring measurements. Off-finger / failed readings come back as all-ones
 * (0xFF for 1-byte values, 0xFFFF for 2-byte values) or as values outside these ranges.
 */
object Valid {
    const val FAIL_MSG = "Measurement failed - wear the ring snugly"
    val TEMP_C = 20.0..45.0
    fun range(m: Metric): ClosedFloatingPointRange<Double>? = when (m) {
        Metric.HEART_RATE -> 25.0..250.0
        Metric.SPO2 -> 50.0..100.0
        Metric.HRV -> 1.0..2000.0
        Metric.STRESS -> 1.0..100.0
        Metric.TEMP -> TEMP_C
        Metric.ENERGY -> 0.1..1000.0      // generous: only drops 0 placeholders and 0xFFFF (6553.5) until the real range is known
        else -> null
    }
    fun ok(m: Metric, v: Double) = range(m)?.contains(v) ?: true
    /** Raw all-ones sentinel for a [width]-byte value (0xFF, 0xFFFF). */
    fun allOnes(raw: Int, width: Int) = raw == (1 shl (8 * width)) - 1
}

data class Sample(val metric: Metric, val time: Long, val value: Double, val source: String)
/** One ring sleep session (7/05). Times are phone-epoch ms. */
data class SleepSession(val id: Int, val type: Int, val start: Long, val end: Long, val sleepMinutes: Float, val efficiency: Float, val score: Float) {
    val isNap get() = type == 2
}
data class SleepSeg(val stage: Int, val start: Long, val end: Long) { val minutes get() = (end - start) / 60000 }  // 0 awake,1 light,2 deep,3 REM

sealed class RingEvent {
    data class Live(val metric: Metric, val value: Double) : RingEvent()
    data class History(val samples: List<Sample>) : RingEvent()
    /** One page of a 5-minute "timing" table; [values] are null when invalid. */
    data class TimingPage(val cmd: Int, val metric: Metric, val day: Int, val page: Int, val values: List<Double?>) : RingEvent()
    data class Sleep(val day: Int, val segments: List<SleepSeg>, val raw: ByteArray) : RingEvent()
    data class StepsDay(val day: Int, val steps: Int, val distance: Int, val calories: Int) : RingEvent()
    data class StepsDetails(val day: Int, val perHalfHour: List<Int>) : RingEvent()
    data class CurrentSteps(val steps: Int, val distance: Int, val calories: Int) : RingEvent()
    data class Battery(val percent: Int, val millivolts: Int? = null) : RingEvent()
    data class Firmware(val version: String) : RingEvent()
    data class DeviceInfo(val name: String, val version: String) : RingEvent()
    data class TimingState(val cmd: Int, val raw: ByteArray) : RingEvent()
    /** 2/06 HR, 2/07 HRV, 2/08 SpO2, 2/2D stress: byte0 = interval in 5-minute steps (0 = off), per n4.s.b() = minutes/5. */
    data class TimingInterval(val cmd: Int, val steps: Int) : RingEvent()
    /** 2/0x25 querySupportBloodOxygenType: 0 not supported, 1 sleep-only SpO2, 2 all-day timing SpO2 (CRPBloodOxygenType). */
    data class SpO2Type(val type: Int) : RingEvent()
    /** 7/00 querySupportGomore: [enabled, version]; 7/01 querySavedGomoreKey: [saved]. */
    data class GomoreSupport(val supported: Boolean, val version: Int) : RingEvent()
    data class GomoreKey(val saved: Boolean) : RingEvent()
    data class SleepState(val state: Int) : RingEvent()
    data class MeasureFailed(val metric: Metric, val reason: String) : RingEvent()
    /** 2/0x13 (decimal 19) queryActionDetails page: raw per-minute bytes (decode with [Actigraphy.magnitude]). */
    data class ActionPage(val day: Int, val page: Int, val values: List<Int>) : RingEvent()
    /** 7/04 session list: phone-epoch ms session starts; session id = list index. */
    data class SleepList(val starts: List<Long>) : RingEvent()
    /** 7/05 session details. type 1 = long sleep, 2 = short nap. */
    data class SleepDetails(val session: SleepSession) : RingEvent()
    /** 7/06 hypnogram by session id (same triplet layout as 2/0E). [raw] is the payload after the id byte. */
    data class SessionStages(val id: Int, val raw: ByteArray) : RingEvent()
    data class Unknown(val group: Int, val cmd: Int, val payload: ByteArray) : RingEvent()
}

/** Decodes complete FDD3 frames and characteristic values (mirrors g1.a dispatch). */
class Parser(private val tz: TimeZone = TimeZone.getDefault(), private val now: () -> Long = { System.currentTimeMillis() }) {
    fun dayStart(day: Int): Long = Calendar.getInstance(tz).apply { timeInMillis = now(); add(Calendar.DAY_OF_MONTH, -day)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    fun frame(f: ByteArray): RingEvent? {
        if (f.size < 6 || f[0] != Frame.H0 || f[1] != Frame.H1) return null
        val g = u8(f, 4); val c = u8(f, 5); val p = f.copyOfRange(6, f.size)
        return when (g) {
            1 -> when (c) {
                9 -> live(Metric.HEART_RATE, p); 10 -> live(Metric.HRV, p); 11 -> live(Metric.SPO2, p); 14 -> live(Metric.STRESS, p)
                32 -> liveTemp(p)
                25 -> if (p.size >= 2) RingEvent.History(listOf(Sample(Metric.BP_SYS, now(), u8(p, 0).toDouble(), "live"), Sample(Metric.BP_DIA, now(), u8(p, 1).toDouble(), "live"))) else null
                else -> RingEvent.Unknown(g, c, p)
            }
            2 -> when (c) {
                9 -> measured(p, Metric.HEART_RATE, 5); 11 -> measured(p, Metric.SPO2, 5); 25 -> measured(p, Metric.STRESS, 5); 10 -> measured(p, Metric.HRV, 6)
                13 -> if (p.size >= 13) RingEvent.StepsDay(u8(p, 0), le24(p, 1), le24(p, 5), le24(p, 9)) else null
                18 -> if (p.isNotEmpty()) RingEvent.StepsDetails(u8(p, 0), (1 until p.size - 1 step 2).map { le16(p, it) }) else null
                14 -> if (p.isNotEmpty()) sleep(p) else null
                19 -> if (p.size >= 2) RingEvent.ActionPage(u8(p, 0), u8(p, 1), (2 until p.size).map { u8(p, it) }) else null
                15 -> timing(c, Metric.HEART_RATE, p, 1) { v -> v.takeIf { it in 40..200 }?.toDouble() }
                17 -> timing(c, Metric.SPO2, p, 1) { v -> v.takeIf { it in 1..100 }?.toDouble() }
                47 -> timing(c, Metric.STRESS, p, 1) { v -> v.takeIf { it in 1..100 }?.toDouble() }
                16 -> timing(c, Metric.HRV, p, 2) { v -> v.takeIf { it in 1..2000 }?.toDouble() }
                22 -> timing(c, Metric.TEMP, p, 2) { v -> if (Valid.allOnes(v, 2)) null else (v / 10.0).takeIf { it in Valid.TEMP_C } }
                6, 7, 8, 45 -> if (p.isNotEmpty()) RingEvent.TimingInterval(c, u8(p, 0)) else RingEvent.TimingState(c, p)
                21 -> RingEvent.TimingState(c, p)
                37 -> if (p.isNotEmpty()) RingEvent.SpO2Type(u8(p, 0)) else null
                48 -> RingEvent.SleepState(when { p.size == 2 -> u8(p, 1); p.isNotEmpty() -> if (p[0] > 0) 1 else 0; else -> 0 })
                else -> RingEvent.Unknown(g, c, p)
            }
            3 -> when (c) {
                3 -> RingEvent.Firmware(String(p, Charsets.UTF_8).trim { it <= ' ' })
                6 -> if (p.isNotEmpty()) RingEvent.Battery(u8(p, 0), if (p.size >= 3) le16(p, 1) else null) else null
                else -> RingEvent.Unknown(g, c, p)
            }
            7 -> when (c) {
                0 -> RingEvent.GomoreSupport(p.isNotEmpty() && p[0].toInt() != 0, if (p.size > 1) u8(p, 1) else 0)
                1 -> RingEvent.GomoreKey(p.isNotEmpty() && p[0].toInt() != 0)
                12 -> if (p.size >= 2) le16(p, 0).let { raw -> val v = raw / 10.0
                        if (Valid.allOnes(raw, 2) || raw == 0 || !Valid.ok(Metric.ENERGY, v)) null else RingEvent.Live(Metric.ENERGY, v) } else null
                14 -> timing(Cmd.ENERGY_TABLE, Metric.ENERGY, p, 2) { v -> if (Valid.allOnes(v, 2) || v == 0) null else (v / 10.0).takeIf { Valid.ok(Metric.ENERGY, it) } }
                4 -> if (p.size % 4 == 0) RingEvent.SleepList((p.indices step 4).map { RingTime.fromDevice(le32(p, it), tz) }) else null
                5 -> sleepDetails(p)
                6 -> if (p.isNotEmpty()) RingEvent.SessionStages(u8(p, 0), p) else null
                10 -> RingEvent.SleepState(when { p.size == 2 -> u8(p, 1); p.isNotEmpty() -> if (p[0] > 0) 1 else 0; else -> 0 })
                else -> RingEvent.Unknown(g, c, p)
            }
            5 -> if (c == 2 && p.isNotEmpty()) deviceInfo(p) else RingEvent.Unknown(g, c, p)
            else -> RingEvent.Unknown(g, c, p)
        }
    }

    private fun live(m: Metric, p: ByteArray): RingEvent? {
        if (p.isEmpty()) return null
        val v = u8(p, 0)
        // Real ring: 0xFF (or 0xFFFF if a 2-byte value is sent) = failed/cancelled/off-finger
        if (Valid.allOnes(v, 1) || (p.size >= 2 && Valid.allOnes(le16(p, 0), 2))) return RingEvent.MeasureFailed(m, Valid.FAIL_MSG)
        if (v == 0) return null
        if (!Valid.ok(m, v.toDouble())) return RingEvent.MeasureFailed(m, Valid.FAIL_MSG)
        return RingEvent.Live(m, v.toDouble())
    }

    /** 1/0x20 result: LE16 in 0.1 °C. 0xFFFF (ring off finger) or anything outside 20–45 °C = failed. */
    private fun liveTemp(p: ByteArray): RingEvent? {
        if (p.isEmpty()) return null
        if (p.size < 2) return if (Valid.allOnes(u8(p, 0), 1)) RingEvent.MeasureFailed(Metric.TEMP, Valid.FAIL_MSG) else null
        val raw = le16(p, 0)
        val c = raw / 10.0
        if (Valid.allOnes(raw, 2) || c !in Valid.TEMP_C) return RingEvent.MeasureFailed(Metric.TEMP, Valid.FAIL_MSG)
        return RingEvent.Live(Metric.TEMP, c)
    }

    /** [n][value(1 or 2) + ts(4)]* — e1.f.g / d.g / l.c / g.c */
    private fun measured(p: ByteArray, m: Metric, rec: Int): RingEvent? {
        if (p.size % rec != 1) return null
        val vlen = rec - 4
        return RingEvent.History((1 until p.size step rec).mapNotNull { i ->
            val v = if (vlen == 2) le16(p, i) else u8(p, i)
            if (v == 0 || Valid.allOnes(v, vlen) || !Valid.ok(m, v.toDouble())) null
            else Sample(m, RingTime.fromDevice(le32(p, i + vlen), tz), v.toDouble(), "measured")
        })
    }

    private fun timing(cmd: Int, m: Metric, p: ByteArray, width: Int, conv: (Int) -> Double?): RingEvent? {
        if (p.size < 2) return null
        val vals = ArrayList<Double?>()
        var i = 2
        while (i + width <= p.size) { vals += conv(if (width == 2) le16(p, i) else u8(p, i)); i += width }
        return RingEvent.TimingPage(cmd, m, u8(p, 0), u8(p, 1), vals)
    }

    /** [day][state hh mm]* — state of the *previous* point lasts until this point (e1.j.b). */
    fun sleep(p: ByteArray): RingEvent? {
        if (p.size % 3 != 1) return null
        val day = u8(p, 0)
        val base = dayStart(day)                          // midnight of the morning you wake
        // First point at hour>=12 is on the evening before; otherwise on `day`.
        val firstH = if (p.size >= 4) u8(p, 2) else 0
        var cursor = if (firstH >= 12) base - 24 * 3600_000L else base
        val segs = ArrayList<SleepSeg>()
        var prevState = -1; var prevT = 0L
        for (i in 1 until p.size step 3) {
            val st = u8(p, i); val h = u8(p, i + 1); val mi = u8(p, i + 2)
            val cal = Calendar.getInstance(tz).apply { timeInMillis = cursor; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            var t = cal.timeInMillis
            if (t < cursor) t += 24 * 3600_000L            // crossed midnight
            if (prevState >= 0 && t >= prevT) segs += SleepSeg(prevState, prevT, t)
            prevState = st; prevT = t; cursor = t
        }
        return RingEvent.Sleep(day, segs, p)
    }

    /** e1.j.f: [id, type, start32, end32, sleepTime f32, efficiency f32, score f32] (all LE). */
    fun sleepDetails(p: ByteArray): RingEvent? {
        if (p.size < 22) return null
        fun f32(o: Int) = java.lang.Float.intBitsToFloat(le32(p, o).toInt())
        return RingEvent.SleepDetails(SleepSession(u8(p, 0), u8(p, 1), RingTime.fromDevice(le32(p, 2), tz), RingTime.fromDevice(le32(p, 6), tz), f32(10), f32(14), f32(18)))
    }

    /**
     * Triplets `[hdr][stage hh mm]*` anchored to a known session start (7/06). The first point is placed on
     * whichever calendar day puts it nearest [anchor]; later points move forward across midnight.
     */
    fun stagesAt(p: ByteArray, anchor: Long): List<SleepSeg> {
        if (p.size % 3 != 1 || p.size < 4) return emptyList()
        val segs = ArrayList<SleepSeg>()
        var prevState = -1; var prevT = 0L; var cursor = 0L
        for (i in 1 until p.size step 3) {
            val st = u8(p, i); val h = u8(p, i + 1); val mi = u8(p, i + 2)
            val base = if (prevState < 0) anchor else cursor
            var t = Calendar.getInstance(tz).apply { timeInMillis = base; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            if (prevState < 0) { if (t - anchor > 12 * 3600_000L) t -= 86400_000L else if (anchor - t > 12 * 3600_000L) t += 86400_000L }
            else if (t < cursor) t += 86400_000L
            if (prevState >= 0) segs += SleepSeg(prevState, prevT, t)
            prevState = st; prevT = t; cursor = t
        }
        return segs
    }

    private fun deviceInfo(p: ByteArray): RingEvent? = try {
        val n1 = u8(p, 0); val name = String(p, 1, n1, Charsets.UTF_8); val n2 = u8(p, 1 + n1)
        RingEvent.DeviceInfo(name, String(p, 2 + n1, n2, Charsets.UTF_8))
    } catch (e: Exception) { null }

    /** FDD1 value: steps, distance, calories as 3-byte LE each (e1.k.b) */
    fun currentSteps(v: ByteArray): RingEvent? {
        // e1.k.b: length % 3 == 0; first 9 bytes = steps/distance/calories as 3-byte LE each
        if (v.size < 9 || v.size % 3 != 0) return null
        return RingEvent.CurrentSteps(le24(v, 0), le24(v, 3), le24(v, 6))
    }
    /** 2A37 (g1.a.B): bpm = byte[1]; if 4+ bytes the SDK only accepts it when LE16 at [2] == 1024 */
    fun heartRateChar(v: ByteArray): RingEvent? {
        if (v.size < 2) return null
        if (v.size >= 4 && le16(v, 2) != 1024) return null
        val bpm = u8(v, 1)
        // streamed value: 0 / 0xFF / implausible = no skin contact → just ignore it
        if (bpm == 0 || Valid.allOnes(bpm, 1) || !Valid.ok(Metric.HEART_RATE, bpm.toDouble())) return null
        return RingEvent.Live(Metric.HEART_RATE, bpm.toDouble())
    }
}

/** Assembles paged 5-minute tables into per-sample lists (e1.f/d/g/l/m). */
class TimingCollector(private val parser: Parser) {
    private val pages = HashMap<String, MutableMap<Int, List<Double?>>>()
    private val first = HashMap<String, Int>()
    /** Incremental day-0 fetch starts at [page]; earlier pages are already stored and are padded with nulls (not written). */
    fun startAt(cmd: Int, day: Int, page: Int) { first["$cmd/$day"] = page; pages.remove("$cmd/$day") }
    /** returns (nextRequest or null, completed samples or null) */
    fun onPage(e: RingEvent.TimingPage): Pair<ByteArray?, List<Sample>?> {
        val total = Cmd.TIMING[e.cmd] ?: 1
        val key = "${e.cmd}/${e.day}"
        val m = pages.getOrPut(key) { HashMap() }; m[e.page] = e.values
        if (e.page + 1 < total) return Cmd.timingPage(e.cmd, e.day, e.page + 1) to null
        pages.remove(key)
        val f0 = first.remove(key) ?: 0
        if ((f0 until total).any { it !in m }) return null to emptyList()
        val per = m[f0]!!.size
        val all = (0 until total).flatMap { m[it] ?: List<Double?>(per) { null } }
        val start = parser.dayStart(e.day)
        val limit = if (e.day == 0) System.currentTimeMillis() else Long.MAX_VALUE
        return null to all.mapIndexedNotNull { i, v -> val t = start + i * 5 * 60_000L; if (v == null || t > limit) null else Sample(e.metric, t, v, "timing") }
    }
}

/** Per-minute movement (f4.d / SleepActionEntity): value > 128 means movement of magnitude value-128, else still. */
object Actigraphy {
    const val PAGES = 8
    fun magnitude(raw: Int): Int = if (raw > 128) raw - 128 else 0
}

/** Assembles the 8 pages of 2/0x13 into one day of per-minute raw values (e1.a). */
class ActionCollector {
    private val pages = HashMap<Int, MutableMap<Int, List<Int>>>()
    private val first = HashMap<Int, Int>()
    /** Incremental fetch from [page]; skipped earlier minutes come back as -1 (= keep stored value). */
    fun startAt(day: Int, page: Int) { first[day] = page; pages.remove(day) }
    /** Returns (next page request or null, completed day values or null). Missing pages yield an empty list. */
    fun onPage(e: RingEvent.ActionPage): Pair<ByteArray?, List<Int>?> {
        val m = pages.getOrPut(e.day) { HashMap() }; m[e.page] = e.values
        if (e.page + 1 < Actigraphy.PAGES) return Cmd.actionDetails(e.day, e.page + 1) to null
        pages.remove(e.day)
        val f0 = first.remove(e.day) ?: 0
        if ((f0 until Actigraphy.PAGES).any { it !in m }) return null to emptyList()
        val per = m[f0]!!.size
        return null to (0 until Actigraphy.PAGES).flatMap { m[it] ?: List(per) { -1 } }
    }
}

/** Ring periodic-measurement settings (read-only queries 2/06, 2/07, 2/08, 2/2D, 2/15). */
object Intervals {
    val QUERY_CMDS = listOf(6, 7, 8, 45, 21, 37)
    val NAMES = mapOf(6 to "HR", 7 to "HRV", 8 to "SpO2", 45 to "stress")
    fun minutes(steps: Int) = steps * 5
    /** [steps] cmd -> interval steps; [tempState] raw 2/15 payload (byte0 state, byte1 type) or null. */
    fun describe(steps: Map<Int, Int>, tempState: ByteArray?): String {
        val parts = listOf(6, 7, 8, 45).mapNotNull { c -> steps[c]?.let { v -> "${NAMES[c]} " + if (v <= 0) "off" else "every ${minutes(v)} min" } }
        val temp = tempState?.takeIf { it.isNotEmpty() }?.let { "temp " + if (it[0].toInt() == 0) "off" else "on (state ${it[0].toInt() and 0xFF}${if (it.size > 1) ", type ${it[1].toInt() and 0xFF}" else ""})" }
        val all = parts + listOfNotNull(temp)
        return if (all.isEmpty()) "Ring measurement intervals not read yet (connect and sync)" else "Ring measures " + all.joinToString(", ")
    }
}

/**
 * Periodic-measurement writes, exactly as the SDK's b1.t/u/h/h0.c(int) build them: group 1, one payload byte =
 * interval in 5-minute steps (n4.s.b() = minutes / 5); 0 = off (disableTiming* sends the same command with 0).
 * There is no separate enable flag. Read back with the matching group-2 query.
 */
object AutoMeasure {
    /** Stock HR list (n4.s): 5,10,15,20,25,30,60 min; stock SpO2/stress toggles send 12 (60 min) or 0. */
    val ALLOWED_MINUTES = listOf(0, 5, 10, 15, 20, 25, 30, 60)
    val METRICS = listOf(Metric.HEART_RATE, Metric.SPO2, Metric.HRV, Metric.STRESS)
    fun writeCmd(m: Metric) = when (m) { Metric.HEART_RATE -> 6; Metric.HRV -> 7; Metric.SPO2 -> 8; Metric.STRESS -> 39; else -> throw IllegalArgumentException("$m") }
    fun queryCmd(m: Metric) = when (m) { Metric.HEART_RATE -> 6; Metric.HRV -> 7; Metric.SPO2 -> 8; Metric.STRESS -> 45; else -> throw IllegalArgumentException("$m") }
    fun metricForQuery(cmd: Int): Metric? = METRICS.firstOrNull { queryCmd(it) == cmd }
    fun steps(minutes: Int): Int { require(minutes in ALLOWED_MINUTES) { "unsupported interval $minutes" }; return minutes / 5 }
    fun write(m: Metric, minutes: Int): ByteArray = Frame.build(1, writeCmd(m), byteArrayOf(steps(minutes).toByte()))
    fun query(m: Metric): ByteArray = Frame.build(2, queryCmd(m))
    fun querySpO2Type(): ByteArray = Frame.build(2, 37)
    fun label(steps: Int?) = when { steps == null -> "unknown"; steps <= 0 -> "Off"; else -> "${steps * 5} min" }
    fun spo2TypeLabel(t: Int?) = when (t) { null -> "unknown"; 0 -> "not supported"; 1 -> "sleep only (stock app: \"Blood oxygen during sleep\")"; 2 -> "all-day timing"; else -> "type $t" }
}

/** Feature verdicts shown instead of empty charts. */
object Support {
    /** null = supported/unknown; otherwise the message to show. */
    fun spo2(type: Int?): String? = when (type) { 0 -> "Periodic SpO2: not supported by this ring (2/25 type 0)"; 1 -> "Periodic SpO2 runs only during sleep on this ring (2/25 type 1)"; else -> null }
    fun energy(hasData: Boolean, gomoreSupported: Boolean?, keySaved: Boolean?, tableAnswered: Boolean?): String? = when {
        hasData -> null
        gomoreSupported == false -> "Energy (physical strength): not supported by this ring (no Gomore engine, 7/00)"
        keySaved == false -> "Energy (physical strength): not available — the ring's Gomore engine has no licence key (7/01 = 0); the stock app fetches it from MoYoung's cloud"
        tableAnswered == false -> "Energy (physical strength): not supported by this ring (7/0E not answered)"
        else -> null
    }
}
