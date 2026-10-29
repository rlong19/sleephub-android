package com.rlong.sleephub.ring.proto

import java.util.TimeZone
import java.util.UUID

/**
 * MoYoung / CRP ring BLE protocol, recreated from the Da Rings 1.3.5 SDK (com.crrepa.ble, obfuscated b1/e1/g1/k1).
 *
 * GATT (k1.a): service 0000FDDA-…; write FDD2 (commands), notify FDD3 (framed responses),
 * notify+read FDD1 (current steps), FDD5 (OTA write), FDD6 (watch-face/recording stream), plus standard
 * 180F/2A19 battery, 180D/2A37 heart rate, 180A/2A28,2A29 device info.
 *
 * Frame (b1.q.c): FD DA | 0x10 (bit0 = length bit 8) | length(total, incl. header) | group | cmd | payload…
 * No checksum. Responses on FDD3 may span several notifications; reassembled by total length (g1.a.k).
 * Multi-byte integers are little-endian. Timestamps are seconds of the phone's *wall clock expressed as GMT+8*.
 */
object Uuids {
    private fun u16(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")
    val SERVICE = u16("fdda"); val WRITE = u16("fdd2"); val NOTIFY = u16("fdd3"); val STEPS = u16("fdd1")
    val OTA = u16("fdd5"); val STREAM = u16("fdd6")
    val BATTERY_SVC = u16("180f"); val BATTERY = u16("2a19"); val HR_SVC = u16("180d"); val HR = u16("2a37")
    val DIS_SVC = u16("180a"); val SW_REV = u16("2a28"); val MANUFACTURER = u16("2a29")
    val CCCD = u16("2902")
}

object Frame {
    const val H0 = 0xFD.toByte(); const val H1 = 0xDA.toByte()
    fun build(group: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val n = payload.size + 6
        require(n <= 511)
        return byteArrayOf(H0, H1, (0x10 or ((n shr 8) and 1)).toByte(), (n and 0xFF).toByte(), group.toByte(), cmd.toByte()) + payload
    }
    fun length(b2: Byte, b3: Byte) = ((b2.toInt() and 1) shl 8) or (b3.toInt() and 0xFF)

    /** Reassembles FDD3 notifications into complete frames (same rule as the SDK). */
    class Assembler {
        private var buf: ByteArray? = null; private var need = 0; private var have = 0
        fun feed(chunk: ByteArray): ByteArray? {
            if (buf == null) {
                if (chunk.size < 4 || chunk[0] != H0 || chunk[1] != H1) return null   // stray fragment
                need = length(chunk[2], chunk[3]); buf = ByteArray(need); have = 0
            }
            val b = buf!!
            val n = minOf(chunk.size, need - have)
            System.arraycopy(chunk, 0, b, have, n); have += n
            if (have >= need) { buf = null; return b }
            return null
        }
        fun reset() { buf = null }
    }
}

/** Ring <-> phone time conversion (b1.e). */
object RingTime {
    private val GMT8 = 8 * 3600_000L
    /** phone epoch ms -> device seconds (wall clock reinterpreted as GMT+8) */
    fun toDevice(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long = (ms + tz.getOffset(ms) - GMT8) / 1000
    /** device seconds -> phone epoch ms */
    fun fromDevice(sec: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val t = sec * 1000
        val raw = tz.rawOffset; var r = t - (raw - GMT8)
        if (tz.inDaylightTime(java.util.Date(t))) r -= tz.dstSavings
        return r
    }
}

fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
fun le24(b: ByteArray, o: Int) = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)
fun le32(b: ByteArray, o: Int) = (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)
fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
fun ByteArray.hex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
fun hexBytes(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Original Da Rings (t3.c): value > 100 means charging; UI clamps the bar to 100 and shows the charging icon (UserSettingFragment.d0). */
data class BatteryReading(val percent: Int, val charging: Boolean) {
    val label get() = if (charging) "Charging $percent%" else "$percent%"
    companion object {
        fun fromRaw(raw: Int): BatteryReading {
            val r = raw and 0xFF
            return if (r > 100) BatteryReading((r - 100).coerceIn(0, 100), true) else BatteryReading(r.coerceIn(0, 100), false)
        }
    }
}

/**
 * Label a framed command/reply. For group-1 measure cmds, payload 01/00 are start/stop TX;
 * any other value (including 0xFF = failed) is an RX result from the ring.
 */
fun describeFrame(f: ByteArray): String {
    if (f.size < 6 || f[0] != Frame.H0 || f[1] != Frame.H1) return "raw"
    val g = f[4].toInt() and 0xFF; val c = f[5].toInt() and 0xFF
    val p = if (f.size > 6) f.copyOfRange(6, f.size) else ByteArray(0)
    fun measureName(base: String): String {
        if (p.isEmpty()) return base
        val v = p[0].toInt() and 0xFF
        return when (v) {
            1 -> "start$base"
            0 -> "stop$base"
            0xFF -> "${base}_failed"
            else -> "${base}_result=$v"
        }
    }
    return when {
        g == 1 && c == 0 -> "userInfo"
        g == 1 && c == 1 -> "syncTime"
        g == 1 && c == 9 -> measureName("MeasureHR")
        g == 1 && c == 10 -> measureName("MeasureHRV")
        g == 1 && c == 11 -> measureName("MeasureSpO2")
        g == 1 && c == 14 -> measureName("MeasureStress")
        g == 1 && c == 32 -> if (p.size >= 2) {
            val raw = le16(p, 0)
            if (raw == 0xFFFF || (raw / 10.0) !in Valid.TEMP_C) "MeasureTemp_failed" else "MeasureTemp_result=${raw / 10.0}"
        } else measureName("MeasureTemp")
        g == 1 && c == 25 -> "measureBP"
        g == 1 && c == 6 -> "timingHR_interval"
        g == 3 && c == 3 -> "firmwareVersion"
        g == 3 && c == 6 -> "realtimeBattery"
        g == 5 && c == 2 -> "deviceInfo"
        g == 9 && c == 2 -> "findRing"
        g == 2 && c == 9 -> "historyHR"
        g == 2 && c == 11 -> "historySpO2"
        g == 2 && c == 13 -> "historySteps"
        g == 2 && c == 14 -> "historySleep"
        g == 2 && c == 19 -> "actionDetails"
        g == 7 && c == 4 -> "sleepList"
        g == 7 && c == 12 -> "currentPhysicalStrength"
        g == 7 && c == 14 -> "physicalStrength_page"
        g == 7 && c == 5 -> "sleepDetails"
        g == 7 && c == 6 -> "sleepInfo"
        g == 2 && c == 15 -> "timingHR_page"
        g == 2 && c == 16 -> "timingHRV_page"
        g == 2 && c == 22 -> "timingTemp_page"
        g == 2 && c == 47 -> "timingStress_page"
        g == 2 && c == 6 -> "timingHR_interval?"
        g == 2 && c == 7 -> "timingHRV_interval?"
        g == 2 && c == 8 -> "timingSpO2_interval?"
        g == 2 && c == 45 -> "timingStress_interval?"
        g == 2 && c == 21 -> "timingTemp_state?"
        g == 2 && c == 10 -> "historyHRV"
        g == 2 && c == 17 -> "timingSpO2_page"
        else -> "g=$g/c=$c"
    }
}

/** Safety-net timeouts (ms). Original app waits for the ring reply; SpO2 often needs >30s (real session failed at 30s stop). */
fun measureTimeoutMs(m: Metric): Long = when (m) {
    Metric.HEART_RATE -> 45_000L
    Metric.SPO2, Metric.HRV, Metric.STRESS -> 90_000L
    Metric.TEMP, Metric.BP_SYS, Metric.BP_DIA -> 60_000L
    else -> 60_000L
}


/** Command builders (group, cmd) taken from the SDK's b1.* classes; names follow CRPBleConnection. */
object Cmd {
    private fun b(on: Boolean) = byteArrayOf(if (on) 1 else 0)
    fun syncTime(nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): ByteArray {
        val s = RingTime.toDevice(nowMs, tz)
        return Frame.build(1, 1, byteArrayOf(s.toByte(), (s shr 8).toByte(), (s shr 16).toByte(), (s shr 24).toByte(), 8))
    }
    fun userInfo(heightCm: Int, weightKg: Int, age: Int, gender: Int, strideCm: Int) =
        Frame.build(1, 0, byteArrayOf(heightCm.toByte(), weightKg.toByte(), age.toByte(), gender.toByte(), strideCm.toByte()))
    fun dailyStepGoal(steps: Int) = Frame.build(1, 2, byteArrayOf(steps.toByte(), (steps shr 8).toByte(), (steps shr 16).toByte(), (steps shr 24).toByte()))  // uncertain layout
    // single measurements (start = 1, stop = 0); result arrives as group 1 same cmd
    fun measureHeartRate(on: Boolean) = Frame.build(1, 9, b(on))
    fun measureHrv(on: Boolean) = Frame.build(1, 10, b(on))
    fun measureSpO2(on: Boolean) = Frame.build(1, 11, b(on))
    fun measureStress(on: Boolean) = Frame.build(1, 14, b(on))
    fun measureBloodPressure(on: Boolean) = Frame.build(1, 25, b(on))
    fun measureTemp(on: Boolean) = Frame.build(1, 32, b(on))
    // automatic ("timing") measurement settings
    fun timingHeartRate(intervalMin: Int) = Frame.build(1, 6, byteArrayOf(intervalMin.coerceAtLeast(0).toByte()))
    fun timingHrv(intervalMin: Int) = Frame.build(1, 7, byteArrayOf(intervalMin.coerceAtLeast(0).toByte()))
    fun timingSpO2(intervalMin: Int) = Frame.build(1, 8, byteArrayOf(intervalMin.coerceAtLeast(0).toByte()))
    fun timingStress(intervalMin: Int) = Frame.build(1, 39, byteArrayOf(intervalMin.coerceAtLeast(0).toByte()))
    fun timingTemp(on: Boolean) = Frame.build(1, 13, b(on))
    fun queryTimingHeartRateState() = Frame.build(2, 6)
    fun queryTimingHrvState() = Frame.build(2, 7)
    fun queryTimingSpO2State() = Frame.build(2, 8)
    fun queryTimingTempState() = Frame.build(2, 21)
    fun queryTimingStressState() = Frame.build(2, 45)
    // history
    fun historySteps(day: Int) = Frame.build(2, 13, byteArrayOf(day.toByte()))
    fun historyStepsDetails(day: Int) = Frame.build(2, 18, byteArrayOf(day.toByte()))
    fun historySleep(day: Int) = Frame.build(2, 14, byteArrayOf(day.toByte()))
    fun historyHeartRate() = Frame.build(2, 9)
    fun historyHrv() = Frame.build(2, 10)
    fun historySpO2() = Frame.build(2, 11)
    fun historyStress() = Frame.build(2, 25)
    fun timingHeartRatePage(day: Int, page: Int) = Frame.build(2, 15, byteArrayOf(day.toByte(), page.toByte()))
    fun timingHrvPage(day: Int, page: Int) = Frame.build(2, 16, byteArrayOf(day.toByte(), page.toByte()))
    fun timingSpO2Page(day: Int, page: Int) = Frame.build(2, 17, byteArrayOf(day.toByte(), page.toByte()))
    fun timingTempPage(day: Int, page: Int) = Frame.build(2, 22, byteArrayOf(day.toByte(), page.toByte()))
    fun timingStressPage(day: Int, page: Int) = Frame.build(2, 47, byteArrayOf(day.toByte(), page.toByte()))
    fun sleepState() = Frame.build(2, 48)
    fun sleepList() = Frame.build(7, 4)
    fun sleepDetails(id: Int) = Frame.build(7, 5, byteArrayOf(id.toByte()))
    fun sleepInfo(id: Int) = Frame.build(7, 6, byteArrayOf(id.toByte()))
    /** 2/0x13 queryActionDetails [day, page]; pages 0-7 (b1.b.a). */
    fun actionDetails(day: Int, page: Int) = Frame.build(2, 19, byteArrayOf(day.toByte(), page.toByte()))
    /** CRPHistoryDay enum: TODAY(0) … DAYS_AGO_14(14). The stock Da Rings app only walks 0..6 (stops at DAYS_AGO_7). */
    const val HISTORY_MAX_DAY = 14
    // device
    fun firmwareVersion() = Frame.build(3, 3)
    fun deviceInfo() = Frame.build(5, 2)
    fun realtimeBattery() = Frame.build(3, 6)
    fun findRing(on: Boolean) = Frame.build(9, 2, b(on))           // c0.c: payload = enable flag (uncertain)
    fun queryVibration() = Frame.build(9, 0, byteArrayOf(0))         // uncertain payload
    fun shutDown() = Frame.build(3, 1)
    fun restart() = Frame.build(3, 14)
    fun queryMeasureState() = Frame.build(2, 24)

    /** Debug variants for live measure — the stock SDK uses V_STOCK (group 1 / cmd, payload 01). */
    enum class MeasureVariant(val label: String) {
        V_STOCK("Stock SDK: g=1 cmd payload=01 (WRITE_NO_RESPONSE)"),
        V_STOCK_DEFAULT("Stock bytes + WRITE_TYPE_DEFAULT"),
        V_PAYLOAD_00("Inverted payload 00 as start"),
        V_NO_PAYLOAD("No payload (len=6)"),
        V_ENABLE_TIMING_THEN_MEASURE("Enable timing interval=5 then stock measure"),
        V_USERINFO_THEN_MEASURE("Send userInfo then stock measure"),
    }
    fun measureVariant(m: Metric, v: MeasureVariant): List<ByteArray> {
        val startCmd = when (m) {
            Metric.HEART_RATE -> 9; Metric.SPO2 -> 11; Metric.HRV -> 10; Metric.STRESS -> 14; Metric.TEMP -> 32
            else -> 9
        }
        return when (v) {
            MeasureVariant.V_STOCK, MeasureVariant.V_STOCK_DEFAULT -> listOf(Frame.build(1, startCmd, byteArrayOf(1)))
            MeasureVariant.V_PAYLOAD_00 -> listOf(Frame.build(1, startCmd, byteArrayOf(0)))
            MeasureVariant.V_NO_PAYLOAD -> listOf(Frame.build(1, startCmd))
            MeasureVariant.V_ENABLE_TIMING_THEN_MEASURE -> listOf(
                when (m) { Metric.HEART_RATE -> timingHeartRate(5); Metric.SPO2 -> timingSpO2(5); Metric.HRV -> timingHrv(5); Metric.STRESS -> timingStress(5); else -> timingHeartRate(5) },
                Frame.build(1, startCmd, byteArrayOf(1)),
            )
            MeasureVariant.V_USERINFO_THEN_MEASURE -> listOf(userInfo(175, 70, 30, 1, 70), Frame.build(1, startCmd, byteArrayOf(1)))
        }
    }

    /** Paging rule: number of pages per timing table (e1.f/d/g/l/m). */
    /** Pseudo cmd id for the 7/0E physical-strength table (4 pages × 72 u16 = 288 five-minute slots, e1.h). */
    const val ENERGY_TABLE = 0x70E
    val TIMING = mapOf(15 to 2, 17 to 2, 16 to 4, 47 to 2, 22 to 4, ENERGY_TABLE to 4)
    fun timingPage(cmd: Int, day: Int, page: Int) =
        if (cmd == ENERGY_TABLE) Frame.build(7, 14, byteArrayOf(day.toByte(), page.toByte())) else Frame.build(2, cmd, byteArrayOf(day.toByte(), page.toByte()))
    fun currentEnergy() = Frame.build(7, 12)
}
