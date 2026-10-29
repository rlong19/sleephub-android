/*
 * EMAY SleepO2 BLE protocol — ported/adapted from emay-sleepo2 (chenders/emay-sleepo2, PyPI emay-sleepo2 1.0.6).
 *
 * MIT License
 * Copyright (c) 2026 Ground Effect Software, LLC (emay-sleepo2 authors)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.rlong.sleephub.oxi

import java.util.UUID

/** One 1 Hz reading; null = finger off / sensor sentinel for that metric. */
data class OxiReading(val time: Long, val spo2: Int?, val pulse: Int?) {
    val fingerOn get() = spo2 != null || pulse != null
    /** Same reading with sentinel / out-of-range values (finger-off 255, 127, 0, …) replaced by null. */
    fun sanitized(): OxiReading { val s = OxiValid.spo2(spo2); val p = OxiValid.pulse(pulse); return if (s == spo2 && p == pulse) this else OxiReading(time, s, p) }
}

/** One validity rule for every oximeter path (live BLE, device history, CSV, DB read): SpO2 1..100 %, pulse 25..250 bpm, else null. */
object OxiValid {
    val SPO2 = 1..100; val PULSE = 25..250
    fun spo2(v: Int?) = v?.takeIf { it in SPO2 }
    fun pulse(v: Int?) = v?.takeIf { it in PULSE }
}

/**
 * Pure protocol layer (v0.3.1: extended from the decompiled EMAY app, see emay-sleepo2-protocol.md). Only read /
 * stream / history-download commands (+ opt-in clock set) exist; delete (9D 7F) and E2/E3 frames are hard-blocked in [guard].
 */
object EmayProtocol {
    val SERVICE: UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val NAME_PREFIX = "SleepO2"
    /** Name prefixes accepted by the official app (BleScanHelper, checked against BOTH BluetoothDevice.getName() and the
     *  scan-record local name). Only SleepO2 / SleepO2-2 use this FF12 protocol; the others are listed so they show up. */
    val NAME_PREFIXES = listOf("SleepO2-2", "SleepO2", "EMAY Oximeter", "EMAY BT Oximeter", "EM90", "EMO-100", "EM60", "EMO-80")
    /** Pure matcher (unit-tested): FF12 service advertised, or either name starts with a known prefix / contains "emay". */
    fun isEmayName(vararg names: String?): Boolean = names.any { n -> n != null &&
        (NAME_PREFIXES.any { n.trim().startsWith(it, true) } || n.contains("emay", true) || n.contains("sleepo2", true)) }
    const val HEARTBEAT_MS = 1500L

    // payloads WITHOUT checksum
    val HELLO = bytes(0x89)
    val DEVICE_STATE = bytes(0x8E, 0x05)
    val START_REALTIME = bytes(0x9B, 0x01)
    val STOP_REALTIME = bytes(0x9B, 0x7F)
    val GET_BATTERY = bytes(0x86)
    val HEARTBEAT = bytes(0x9A)

    val STORAGE_SUMMARY = bytes(0x90, 0x06)
    val NEXT_SEGMENT = bytes(0x9C, 0x01)

    val START_SEQUENCE: List<ByteArray> get() = listOf(HELLO, DEVICE_STATE, START_REALTIME, GET_BATTERY).map { command(it) }
    private val FIXED: List<ByteArray> get() = listOf(HELLO, DEVICE_STATE, START_REALTIME, STOP_REALTIME, GET_BATTERY, HEARTBEAT, STORAGE_SUMMARY, NEXT_SEGMENT).map { command(it) }

    fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    /** sum(payload) & 0x7F (NOT 0xFF — the device ignores 0xFF-masked checksums). */
    fun checksum(payload: ByteArray, len: Int = payload.size): Int { var s = 0; for (i in 0 until len) s += payload[i].toInt() and 0xFF; return s and 0x7F }
    fun command(payload: ByteArray): ByteArray = payload + checksum(payload).toByte()
    private fun u(b: Byte) = b.toInt() and 0xFF

    /** 9D 01 TT 01 II P0 P1 — download segment item II, type TT (1 SpO2, 2 PR), from packet P (7-bit LE). */
    fun download(type: Int, item: Int, packet: Int) = command(bytes(0x9D, 0x01, type, 0x01, item and 0x7F, packet and 0x7F, (packet shr 7) and 0x7F))
    /** 9D 02 TT 01 II 00 00 — abort the current transfer (before a resume). */
    fun abort(type: Int, item: Int) = command(bytes(0x9D, 0x02, type, 0x01, item and 0x7F, 0, 0))
    /** 83 YY MM DD hh mm ss 00 00 — set device clock (binary). Only sent when the user enabled it AND the device is idle. */
    fun setClock(c: java.util.Calendar) = command(bytes(0x83, c.get(java.util.Calendar.YEAR) % 100, c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND), 0, 0))

    /**
     * Writer guard. Returns null if the frame may be sent, else the reason. HARD blocks first (independent of the
     * allowlist): any 9D 7F… (delete all records) and any E2/E3 (Mrd-stack) frame. Then the allowlist.
     */
    fun guard(f: ByteArray, clockAllowed: Boolean = false): String? {
        if (f.isEmpty()) return "empty"
        if (u(f[0]) == 0x9D && f.size > 1 && u(f[1]) == 0x7F) return "HARD BLOCK: 9D 7F = delete all records"
        if (u(f[0]) == 0xE2 || u(f[0]) == 0xE3) return "HARD BLOCK: E2/E3 Mrd-protocol frame"
        if (u(f.last()) != checksum(f, f.size - 1)) return "bad checksum"
        if (FIXED.any { it.contentEquals(f) }) return null
        if (f.size == 8 && u(f[0]) == 0x9D && (u(f[1]) == 1 || u(f[1]) == 2) && u(f[2]) in 1..2 && u(f[3]) == 1 && (1..6).all { u(f[it]) < 0x80 }) {
            if (u(f[1]) == 2 && (u(f[5]) != 0 || u(f[6]) != 0)) return "abort must have P=0"
            return null
        }
        if (f.size == 10 && u(f[0]) == 0x83 && (1..8).all { u(f[it]) < 0x80 }) return if (clockAllowed) null else "set-clock (83) disabled"
        return "not in read/stream allowlist"
    }

    /**
     * Realtime frame EB 01 FL PR SP .. .. cks (8 bytes). FL is not always 05: PR = PR + 128 if (FL & 0x02).
     * Checksum over bytes 0..6. Returns null for anything that isn't a valid data frame.
     */
    fun parse(raw: ByteArray, time: Long = System.currentTimeMillis()): OxiReading? {
        if (raw.size != 8) return null
        if (u(raw[0]) != 0xEB || u(raw[1]) != 0x01) return null
        if (u(raw[7]) != checksum(raw, 7)) return null
        val prRaw = u(raw[3]); val spRaw = u(raw[4])
        val hi = (u(raw[2]) and 0x02) != 0
        val pr = if (prRaw == 0x00 || prRaw == 0xFF || (prRaw == 0x7F && !hi)) null else (prRaw and 0x7F) + (if (hi) 128 else 0)
        val sp = (spRaw and 0x7F).takeUnless { spRaw == 0x00 || spRaw == 0xFF || spRaw == 0x7F }
        // finger-off sentinels (e.g. PR byte 7F with the +128 flag = 255) become null, never a value; the frame itself is still valid
        return OxiReading(time, OxiValid.spo2(sp), OxiValid.pulse(pr))
    }

    /** Battery reply F6 BB cks → BB & 0x7F (%). */
    fun parseBattery(raw: ByteArray): Int? {
        if (raw.size != 3 || u(raw[0]) != 0xF6 || u(raw[2]) != checksum(raw, 2)) return null
        return (u(raw[1]) and 0x7F).takeIf { it <= 100 }
    }
}
