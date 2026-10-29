package com.rlong.sleephub.oxi

import java.util.Calendar
import java.util.TimeZone

/** Pure decoders for the stored-history replies (see emay-sleepo2-protocol.md §3, §5). */
object EmayHistory {
    const val PACKET = 24; const val PER_PACKET = 27
    private fun u(b: Byte) = b.toInt() and 0xFF
    fun ok(f: ByteArray) = f.size >= 2 && u(f.last()) == EmayProtocol.checksum(f, f.size - 1)
    fun le7(f: ByteArray, from: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = v or ((u(f[from + i]) and 0x7F).toLong() shl (7 * i)); return v }

    data class Summary(val total: Int, val notUploaded: Int, val storage: Long, val free: Long)
    /** E0 06 t0 t1 u0 u1 s0..s3 f0..f3 cks (15). */
    fun summary(f: ByteArray): Summary? = if (f.size == 15 && u(f[0]) == 0xE0 && u(f[1]) == 0x06 && ok(f))
        Summary(le7(f, 2, 2).toInt(), le7(f, 4, 2).toInt(), le7(f, 6, 4), le7(f, 10, 4)) else null

    data class Segment(val user: Int, val item: Int, val start: Long, val length: Int, val last: Boolean, val clockUnset: Boolean) {
        val end get() = start + length * 1000L
        val expectedBytes get() = packets(length) * PACKET
    }
    fun packets(len: Int) = (len + PER_PACKET - 1) / PER_PACKET
    /** EC FL user item YY MM DD hh mm ss L0 L1 L2 L3 … cks (21). Start = device local wall clock in [tz]. */
    fun segment(f: ByteArray, tz: TimeZone = TimeZone.getDefault()): Segment? {
        if (f.size != 21 || u(f[0]) != 0xEC || !ok(f)) return null
        val y = (u(f[4]) and 0x7F) + 2000
        val c = Calendar.getInstance(tz).apply { clear(); set(y, (u(f[5]) and 0x7F) - 1, u(f[6]) and 0x7F, u(f[7]) and 0x7F, u(f[8]) and 0x7F, u(f[9]) and 0x7F) }
        return Segment(u(f[2]) and 0x7F, u(f[3]) and 0x7F, c.timeInMillis, le7(f, 10, 4).toInt(), (u(f[1]) and 0x40) != 0, y == 2037 || y == 2000)
    }

    /** Validates one 24-byte ED 01 packet: header, type, item, sequence number, checksum. */
    fun packetOk(p: ByteArray, type: Int, item: Int, seq: Int): Boolean = p.size == PACKET && u(p[0]) == 0xED && u(p[1]) == 0x01 &&
        u(p[2]) == type && u(p[4]) == item && (u(p[5]) and 0x7F) + (u(p[6]) and 0x7F) * 128 == seq && ok(p)

    /**
     * Decode one packet to 27 samples (null = no reading). Bytes 9..15 get +128 from bits of [7], 16..22 from [8].
     * [9] = absolute first value; [10..22] = 26 nibbles, high first: mag = n&7, 7 = invalid, else prev ± mag (bit 3 = minus).
     * Invalid samples don't move the running value (medium confidence — validate on hardware).
     */
    fun decode(p: ByteArray): List<Int?> {
        val b = IntArray(PACKET) { u(p[it]) }
        for (i in 0 until 7) { b[9 + i] = (b[9 + i] and 0x7F) + (if ((b[7] shr i) and 1 != 0) 128 else 0); b[16 + i] = (b[16 + i] and 0x7F) + (if ((b[8] shr i) and 1 != 0) 128 else 0) }
        val out = ArrayList<Int?>(PER_PACKET)
        var prev = b[9]; out += prev.takeUnless { it == 255 || it == 127 || it == 0 }
        for (k in 10..22) for (nib in intArrayOf(b[k] shr 4, b[k] and 0x0F)) {
            val mag = nib and 7
            if (mag == 7) { out += null; continue }
            prev += if (nib and 8 != 0) -mag else mag
            out += prev.takeUnless { it <= 0 || it >= 255 }
        }
        return out
    }
    /** Decode a full transfer (already validated) and trim to [length] samples. */
    fun decodeAll(packets: List<ByteArray>, length: Int): List<Int?> = packets.flatMap { decode(it) }.take(length)

    /** Merge SpO2 / PR series into 1 Hz readings from [start]. */
    fun readings(start: Long, spo2: List<Int?>, pr: List<Int?>): List<OxiReading> = (0 until maxOf(spo2.size, pr.size)).map { i ->
        OxiReading(start + i * 1000L, OxiValid.spo2(spo2.getOrNull(i)), OxiValid.pulse(pr.getOrNull(i))) }
}
