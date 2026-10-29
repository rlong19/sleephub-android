package com.rlong.sleephub.zeo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Zeo Mobile "HMSG" framing — see /workspace/zeolab/zeo-mobile-protocol.md. Pure Kotlin, unit-tested. */
object Hmsg {
    val MAGIC = byteArrayOf(0x48, 0x4D, 0x53, 0x47) // "HMSG"
    const val HEADER = 12
    const val VERSION = 2

    object T { const val NULL = 0; const val COMMAND = 1; const val ACK = 2; const val STARTING = 3; const val STATE_CHANGE = 4
        const val ACCEL = 5; const val ERROR = 6; const val SLEEP = 7; const val SETUP_BT = 8; const val STATE = 9
        const val TIME_QUERY = 10; const val TIME_REPORT = 11; const val WAKE_NOTIFY = 12; const val WAKE_WINDOW = 13; const val TEST_LED = 163 }
    object Cmd { const val QUERY_STATE = 6; const val SLEEP_SEND = 8 }

    fun bodySize(type: Int): Int = when (type) {
        0 -> 0; 1, 2, 4, 6, 12, 163 -> 4; 3 -> 104; 5 -> 72; 7 -> 1144; 8 -> 56; 9 -> 16; 10 -> 8; 11 -> 12; 13 -> 12; else -> -1
    }
    fun typeName(t: Int) = when (t) {
        0 -> "NULL"; 1 -> "COMMAND_REQUEST"; 2 -> "HB_ACK"; 3 -> "HB_STARTING"; 4 -> "STATE_CHANGE"; 5 -> "REPORT_ACCEL"; 6 -> "REPORT_ERROR"
        7 -> "REPORT_SLEEP"; 8 -> "SET_UP_BT"; 9 -> "STATE_REPORT"; 10 -> "TIME_QUERY"; 11 -> "TIME_REPORT"; 12 -> "WAKEUP_NOTIFY"
        13 -> "WAKEUP_WINDOW"; 163 -> "TEST_LED"; else -> "TYPE_$t"
    }

    private val TABLE = IntArray(256) { i -> var c = i shl 8; repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }; c and 0xFFFF }
    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). Identical to com.myzeo.zeo.utility.Crc. */
    fun crc16(b: ByteArray, off: Int = 0, len: Int = b.size, init: Int = 0xFFFF): Int {
        var crc = init
        for (i in off until off + len) crc = ((crc shl 8) and 0xFFFF) xor TABLE[((crc shr 8) xor (b[i].toInt() and 0xFF)) and 0xFF]
        return crc
    }

    private var seq = 0
    @Synchronized fun nextSeq(): Int { seq = (seq + 1) and 0xFF; return seq }

    /** Build a full frame. Does NOT apply the safety policy — use [ZeoSafety] for anything user-driven. */
    fun build(type: Int, body: ByteArray, seqNo: Int = nextSeq(), ack: Int = 0): ByteArray {
        val f = ByteArray(HEADER + body.size)
        System.arraycopy(MAGIC, 0, f, 0, 4)
        f[6] = VERSION.toByte(); f[7] = type.toByte(); f[8] = ack.toByte(); f[9] = seqNo.toByte()
        f[10] = (body.size and 0xFF).toByte(); f[11] = (body.size shr 8).toByte()
        System.arraycopy(body, 0, f, HEADER, body.size)
        val c = crc16(f, 6, f.size - 6); f[4] = (c and 0xFF).toByte(); f[5] = (c shr 8).toByte()
        return f
    }
    fun command(cmd: Int) = build(T.COMMAND, byteArrayOf(cmd.toByte(), 0, 0, 0))
    fun requestStarting() = build(T.NULL, ByteArray(0))
    fun timeQuery(nowMs: Long = System.currentTimeMillis()) = build(T.TIME_QUERY, le().apply { putInt((nowMs / 1000).toInt()); putInt((nowMs % 1000).toInt()) }.array())
    fun testLed(on: Boolean) = build(T.TEST_LED, byteArrayOf(if (on) 1 else 0, 0, 0, 0))
    /** Official clearWakeWindow(): WakeupWindowMessage(offset=EARLIEST, EARLIEST, EARLIEST) → sent=(now-EARLIEST)/1000, begin=0, end=0. */
    const val EARLIEST_SEC = 1075593600L
    fun clearWakeWindow(nowMs: Long = System.currentTimeMillis()) = build(T.WAKE_WINDOW, ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .apply { putInt((nowMs / 1000 - EARLIEST_SEC).toInt()); putInt(0); putInt(0) }.array())
    private fun le() = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    data class Frame(val type: Int, val version: Int, val ack: Int, val seq: Int, val crc: Int, val body: ByteArray, val raw: ByteArray)

    /** Stream re-assembler: tolerant of split/concatenated reads and garbage; resyncs byte-by-byte like the official app. */
    class Assembler(private val onBad: (String, ByteArray) -> Unit = { _, _ -> }) {
        private var buf = ByteArray(0)
        fun reset() { buf = ByteArray(0) }
        fun feed(data: ByteArray): List<Frame> {
            buf += data
            val out = ArrayList<Frame>()
            var i = 0
            while (buf.size - i >= HEADER) {
                if (!(buf[i] == MAGIC[0] && buf[i + 1] == MAGIC[1] && buf[i + 2] == MAGIC[2] && buf[i + 3] == MAGIC[3])) { i++; continue }
                val type = buf[i + 7].toInt() and 0xFF
                val len = (buf[i + 10].toInt() and 0xFF) or ((buf[i + 11].toInt() and 0xFF) shl 8)
                val ver = buf[i + 6].toInt() and 0xFF
                val known = bodySize(type)
                // Unknown/test types: accept if length is sane so they get logged (official app would drop them).
                if (ver > VERSION + 5 || len > 2048 || (known >= 0 && known != len)) { onBad("bad header type=$type len=$len ver=$ver", buf.copyOfRange(i, i + HEADER)); i++; continue }
                if (buf.size - i < HEADER + len) break
                val raw = buf.copyOfRange(i, i + HEADER + len)
                val crc = (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)
                if (crc16(raw, 6, raw.size - 6) != crc) { onBad("crc mismatch type=$type", raw); i++; continue }
                out += Frame(type, ver, raw[8].toInt() and 0xFF, raw[9].toInt() and 0xFF, crc, raw.copyOfRange(HEADER, raw.size), raw)
                i += raw.size
            }
            buf = buf.copyOfRange(i, buf.size)
            if (buf.size > 8192) buf = buf.copyOfRange(buf.size - 4096, buf.size)
            return out
        }
    }
}

/** Sleep stage codes as used by the headband (SleepCommon). */
enum class ZStage(val code: Int, val label: String) { UNDEFINED(0, "—"), WAKE(1, "Wake"), REM(2, "REM"), LIGHT(3, "Light"), DEEP(4, "Deep");
    companion object { fun of(c: Int) = values().firstOrNull { it.code == c } ?: if (c == 6) LIGHT else UNDEFINED } }

sealed class ZeoMsg {
    data class Starting(val deviceName: String, val hwVersion: Long, val model: String, val serial: String, val swVersion: Long,
                        val btAddress: String, val lastAssertFn: String, val lastAssertLine: Long, val resetCause: Int, val queryResponse: Boolean, val resetCount: Int) : ZeoMsg()
    data class State(val activeForced: Boolean, val btLocked: Boolean, val demoMode: Boolean, val docked: Boolean, val onHead: Boolean,
                     val requiresPin: Boolean, val wasCharged: Boolean, val wasQueried: Boolean, val voltage: Int, val voltageStatus: Int,
                     val lastAlarmReason: Int, val algorithmMode: Int, val sensorUseSeconds: Long) : ZeoMsg() {
        val voltageLabel get() = when (voltageStatus) { 1 -> "charged"; 2 -> "charging"; 3 -> "on battery"; 4 -> "TOO LOW"; else -> "none" }
        val algorithmLabel get() = when (algorithmMode) { 0 -> "idle"; 1 -> "tentative active"; 2 -> "ACTIVE (recording)"; 3 -> "tentative idle"; else -> "?$algorithmMode" }
    }
    data class StateChange(val event: Int) : ZeoMsg() { val label get() = EVENTS.getOrElse(event) { "event $event" } }
    data class Ack(val prevSeq: Int) : ZeoMsg()
    data class Error(val code: Int) : ZeoMsg() { val label get() = listOf("none", "invalid name", "invalid PIN", "PIN needed", "PIN not needed", "reset needed", "wrong channel").getOrElse(code) { "error $code" } }
    data class TimeReport(val timeMs: Long, val isOffset: Boolean, val negative: Boolean, val querySeq: Int) : ZeoMsg()
    data class TimeQuery(val timeMs: Long) : ZeoMsg()
    data class WakeNotify(val reason: Int) : ZeoMsg()
    data class Accel(val diffVecMagSums: List<Long>, val greatest: List<Triple<Int, Int, Int>>, val sampleAverage: Int) : ZeoMsg()
    data class Sleep(val displayStart: Long, val awakenings: Int, val timeInDeep: Int, val timeInLight: Int, val timeInRem: Int, val timeInWake: Int,
                     val timeToZ: Int, val totalZ: Int, val zq: Int, val sleepValid: Boolean, val validForHistory: Boolean, val voltageBattery: Int,
                     val userInitiated: Boolean, val stageCounts: List<Int>, val endOfNight: Long, val display: List<ZStage>, val displayStartTime: Long,
                     val insufficientData: Boolean, val startOfNight: Long, val valid: Boolean, val base: List<ZStage>) : ZeoMsg() {
        val currentStage: ZStage get() = base.lastOrNull { it != ZStage.UNDEFINED } ?: ZStage.UNDEFINED
    }
    data class Unknown(val type: Int, val body: ByteArray) : ZeoMsg()
    companion object {
        val EVENTS = listOf("none", "alarm", "alarm window ended", "alarm window started", "docked", "low battery", "off head", "on head",
            "sleep mode changed", "night ended", "night restored", "night saved", "night starting", "sleep onset", "rating needed",
            "sleep state change", "time jump", "undocked", "user sleep locked", "user sleep off", "user sleep on", "user sleep restart")
    }
}

object ZeoParser {
    private class R(b: ByteArray) {
        val bb: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fun u8() = bb.get().toInt() and 0xFF
        fun i8() = bb.get().toInt()
        fun bool() = u8() != 0
        fun u16() = bb.short.toInt() and 0xFFFF
        fun u32() = bb.int.toLong() and 0xFFFFFFFFL
        fun skip(n: Int) { bb.position(bb.position() + n) }
        fun str(n: Int): String { val a = ByteArray(n); bb.get(a); return String(a, Charsets.ISO_8859_1).substringBefore('\u0000').trim() }
        fun bytes(n: Int): ByteArray { val a = ByteArray(n); bb.get(a); return a }
    }
    /** Low nibble first, 2 epochs per byte (Hypnogram.decodeHypnogram). */
    fun decodeHyp(packed: ByteArray, count: Int): List<ZStage> {
        val out = ArrayList<ZStage>(count)
        for (i in 0 until count) { val b = packed[i / 2].toInt(); out += ZStage.of(if (i % 2 == 0) b and 0x0F else (b shr 4) and 0x0F) }
        return out
    }
    fun encodeHyp(stages: List<Int>, maxBytes: Int): ByteArray {
        val out = ByteArray(maxBytes)
        stages.forEachIndexed { i, s -> val idx = i / 2; out[idx] = (if (i % 2 == 0) (out[idx].toInt() and 0xF0) or (s and 0xF) else (out[idx].toInt() and 0x0F) or ((s and 0xF) shl 4)).toByte() }
        return out
    }

    fun parse(f: Hmsg.Frame): ZeoMsg = try { parse(f.type, f.body) } catch (e: Exception) { ZeoMsg.Unknown(f.type, f.body) }

    fun parse(type: Int, body: ByteArray): ZeoMsg {
        val r = R(body)
        return when (type) {
            Hmsg.T.ACK -> ZeoMsg.Ack(r.u8())
            Hmsg.T.STATE_CHANGE -> ZeoMsg.StateChange(r.u8())
            Hmsg.T.ERROR -> ZeoMsg.Error(r.u8())
            Hmsg.T.WAKE_NOTIFY -> ZeoMsg.WakeNotify(r.u8())
            Hmsg.T.STARTING -> {
                val name = r.str(20); r.skip(6); r.u16(); r.u16(); r.u16()
                val hw = r.u32(); val model = r.str(12); val serial = r.str(16); val sw = r.u32()
                val lo = r.u32(); val hi = r.u32(); val addr = (hi shl 32) or lo
                val bt = "%012X".format(addr).chunked(2).joinToString(":")
                ZeoMsg.Starting(name, hw, model, serial, sw, bt, r.str(20), r.u32(), r.u8(), r.bool(), r.u16())
            }
            Hmsg.T.STATE -> ZeoMsg.State(r.bool(), r.bool(), r.bool(), r.bool(), r.bool(), r.bool(), r.bool(), r.bool(), r.u8(), r.u8(), r.u8(), r.u8(), r.u32())
            Hmsg.T.TIME_QUERY -> { val s = r.u32(); val ms = r.u32(); ZeoMsg.TimeQuery(s * 1000 + ms) }
            Hmsg.T.TIME_REPORT -> { val s = r.u32(); val ms = r.u32(); ZeoMsg.TimeReport(s * 1000 + ms, r.bool(), r.bool(), r.u8()) }
            Hmsg.T.ACCEL -> {
                val sums = (0 until 10).map { r.u32() }
                val g = (0 until 10).map { Triple(r.i8(), r.i8(), r.i8()) }
                ZeoMsg.Accel(sums, g, r.u8())
            }
            Hmsg.T.SLEEP -> {
                val dispStart = r.u32(); val aw = r.u16(); val deep = r.u16(); val light = r.u16(); val rem = r.u16(); val wake = r.u16()
                val ttz = r.u16(); val tz = r.u16(); val zq = r.u16()
                val sv = r.bool(); val vh = r.bool(); val vb = r.u8(); val ui = r.bool()
                r.u32(); val sc = (0 until 5).map { r.u16() }; r.u16(); r.u16(); r.u16()
                r.u16(); r.skip(2)
                val eon = r.u32()
                val dc = r.u32().toInt().coerceIn(0, 192); r.u32(); val dp = r.bytes(96); val dst = r.u32()
                val ins = r.bool(); r.skip(3); r.u32(); r.u16(); r.skip(2)
                val son = r.u32(); val valid = r.bool(); r.skip(3)
                val bc = r.u32().toInt().coerceIn(0, 1920); val bp = r.bytes(960)
                ZeoMsg.Sleep(dispStart, aw, deep, light, rem, wake, ttz, tz, zq, sv, vh, vb, ui, sc, eon, decodeHyp(dp, dc), dst, ins, son, valid, decodeHyp(bp, bc))
            }
            else -> ZeoMsg.Unknown(type, body)
        }
    }
}

/**
 * Safety policy for anything sent from the probe screen. Only SAFE-READ / SAFE-TOGGLE
 * messages may leave the phone; DANGEROUS ones are never offered and are rejected here.
 */
object ZeoSafety {
    data class Probe(val label: String, val klass: String, val build: () -> ByteArray)
    val PROBES = listOf(
        Probe("Request HB_STARTING (NULL msg) — device info/firmware", "SAFE-READ") { Hmsg.requestStarting() },
        Probe("QUERY_STATE (cmd 6) — state report", "SAFE-READ") { Hmsg.command(Hmsg.Cmd.QUERY_STATE) },
        Probe("SLEEP_SEND (cmd 8) — sleep report/hypnogram", "SAFE-READ") { Hmsg.command(Hmsg.Cmd.SLEEP_SEND) },
        Probe("TIME_QUERY — headband clock", "SAFE-READ") { Hmsg.timeQuery() },
        Probe("TEST_LED on", "SAFE-TOGGLE") { Hmsg.testLed(true) },
        Probe("TEST_LED off", "SAFE-TOGGLE") { Hmsg.testLed(false) },
        Probe("Clear wake window", "SAFE-TOGGLE") { Hmsg.clearWakeWindow() },
    )
    /** Returns null if allowed, otherwise the reason it is blocked. Input is a full frame (as built) or body-level check. */
    fun check(type: Int, body: ByteArray): String? = when (type) {
        Hmsg.T.NULL -> if (body.isEmpty()) null else "NULL must have empty body"
        Hmsg.T.COMMAND -> { val c = body.getOrNull(0)?.toInt()?.and(0xFF); if (body.size == 4 && (c == 6 || c == 8)) null else "COMMAND $c is not on the SAFE list (only 6 QUERY_STATE, 8 SLEEP_SEND)" }
        Hmsg.T.TIME_QUERY -> if (body.size == 8) null else "TIME_QUERY body must be 8 bytes"
        Hmsg.T.TEST_LED -> if (body.size == 4 && (body[0].toInt() == 0 || body[0].toInt() == 1)) null else "TEST_LED body must be 01/00 + 3 pad"
        Hmsg.T.WAKE_WINDOW -> if (body.size == 12 && body.copyOfRange(4, 12).all { it.toInt() == 0 }) null else "only the 'clear' wake window (begin=0,end=0) is allowed"
        else -> "type $type (${Hmsg.typeName(type)}) is DANGEROUS or unknown — blocked"
    }
    /** Parse user hex "TT BB BB…" (type then body) → frame, or error. */
    fun fromUserHex(hex: String): Pair<ByteArray?, String> {
        val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.length < 2 || clean.length % 2 != 0) return null to "enter: <type byte> <body bytes…> in hex"
        val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val type = bytes[0].toInt() and 0xFF; val body = bytes.copyOfRange(1, bytes.size)
        check(type, body)?.let { return null to "BLOCKED: $it" }
        return Hmsg.build(type, body) to "ok"
    }
}
