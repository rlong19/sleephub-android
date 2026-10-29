package com.rlong.sleephub.ring.sim

import com.rlong.sleephub.ring.ble.Transport
import com.rlong.sleephub.ring.proto.*
import java.util.Random
import java.util.UUID

/**
 * Simulated MoYoung ring. Speaks the same FD DA frames the real firmware does, so the rest of the app
 * (parser, storage, charts) can be exercised without hardware. Only used when the user explicitly
 * enables Demo mode in Settings.
 */
class SimRing(
    private val onNotify: (UUID, ByteArray) -> Unit,
    private val onReady: () -> Unit,
    private val onLost: (String) -> Unit,
    private val onWriteDone: (Int) -> Unit = {},
) : Transport {
    private val rnd = Random(42)
    private var liveHr = false

    init { Thread { Thread.sleep(200); onReady(); emitSteps() }.start() }

    override fun write(frame: ByteArray) {
        if (frame.size < 6) { onWriteDone(0); return }
        val g = frame[4].toInt() and 0xFF; val c = frame[5].toInt() and 0xFF
        val p = frame.copyOfRange(6, frame.size)
        when {
            g == 1 && c == 1 -> reply(3, 6, byteArrayOf(87, 0x48, 0x0F))                 // pretend battery after sync
            g == 3 && c == 3 -> reply(3, 3, "1.3.5-sim".toByteArray())
            g == 5 && c == 2 -> reply(5, 2, byteArrayOf(8) + "Sim Ring".toByteArray() + byteArrayOf(9) + "1.3.5-sim".toByteArray())
            g == 3 && c == 6 -> reply(3, 6, byteArrayOf(87, 0x48, 0x0F))
            g == 1 && c == 9 -> { liveHr = p.isNotEmpty() && p[0] == 1.toByte(); if (liveHr) tickHr() }
            g == 1 && c == 10 -> if (p.isNotEmpty() && p[0] == 1.toByte()) reply(1, 10, byteArrayOf(42))
            g == 1 && c == 11 -> if (p.isNotEmpty() && p[0] == 1.toByte()) reply(1, 11, byteArrayOf(97))
            g == 1 && c == 14 -> if (p.isNotEmpty() && p[0] == 1.toByte()) reply(1, 14, byteArrayOf(35))
            g == 1 && c == 32 -> if (p.isNotEmpty() && p[0] == 1.toByte()) reply(1, 32, byteArrayOf(0x64, 0x01)) // 35.6 °C
            g == 2 && c == 13 -> reply(2, 13, byteArrayOf(p.getOrElse(0) { 0 }) + le(8421, 3) + le(5600, 3) + le(312, 3) + le(0, 4))
            g == 2 && c == 18 -> reply(2, 18, byteArrayOf(p.getOrElse(0) { 0 }) + ByteArray(48 * 2) { i -> if (i % 2 == 0) (5 + rnd.nextInt(40)).toByte() else 0 })
            g == 2 && c == 14 -> { val day = p.getOrElse(0) { 0 }; reply(2, 14, if (day <= 6) sleepNight(day) else byteArrayOf(day)) }
            g == 2 && c in listOf(15, 16, 17, 22, 47) -> if (p.getOrElse(0) { 0 } <= 6) timing(c, p)
            g == 2 && c in listOf(6, 7, 8, 45) -> reply(2, c, byteArrayOf(when (c) { 6 -> 1; 8 -> 6; 45 -> 12; else -> 6 }))
            g == 2 && c == 21 -> reply(2, 21, byteArrayOf(1, 0))
            g == 2 && c == 19 -> { val day = p.getOrElse(0) { 0 }; val page = p.getOrElse(1) { 0 }
                if (day <= 6) reply(2, 19, byteArrayOf(day, page) + ByteArray(180) { i -> val m = page * 180 + i
                    if ((m < 7 * 60 || m > 23 * 60) && rnd.nextInt(10) == 0) (129 + rnd.nextInt(30)).toByte() else if (m in 7 * 60..23 * 60) (129 + rnd.nextInt(80)).toByte() else 0 }) }
            g == 7 && c == 4 -> { val now = System.currentTimeMillis()
                val starts = listOf(now - 33 * 3600_000L, now - 20 * 3600_000L)  // last night + a nap
                reply(7, 4, starts.fold(ByteArray(0)) { a, t -> a + le(RingTime.toDevice(t), 4) }) }
            g == 7 && c == 5 -> { val id = p.getOrElse(0) { 0 }.toInt(); val now = System.currentTimeMillis()
                val st = if (id == 0) now - 33 * 3600_000L else now - 20 * 3600_000L; val en = st + if (id == 0) 8 * 3600_000L else 40 * 60_000L
                reply(7, 5, byteArrayOf(id.toByte(), if (id == 0) 1 else 2) + le(RingTime.toDevice(st), 4) + le(RingTime.toDevice(en), 4) +
                    le(java.lang.Float.floatToIntBits(if (id == 0) 420f else 35f), 4) + le(java.lang.Float.floatToIntBits(88f), 4) + le(java.lang.Float.floatToIntBits(80f), 4)) }
            g == 7 && c == 6 -> reply(7, 6, sleepNight(p.getOrElse(0) { 0 }))
            g == 2 && c == 9 -> reply(2, 9, measured(Metric.HEART_RATE))
            g == 2 && c == 11 -> reply(2, 11, measured(Metric.SPO2))
            g == 2 && c == 10 -> reply(2, 10, measuredHrv())
            g == 2 && c == 25 -> reply(2, 25, measured(Metric.STRESS))
        }
        onWriteDone(0) // GATT_SUCCESS
    }
    private fun tickHr() { Thread { while (liveHr) { reply(1, 9, byteArrayOf((60 + rnd.nextInt(40)).toByte())); Thread.sleep(1000) } }.start() }
    private fun emitSteps() = onNotify(Uuids.STEPS, le(5123, 3) + le(3400, 3) + le(210, 3))

    private fun reply(g: Int, c: Int, payload: ByteArray) {
        val f = Frame.build(g, c, payload)
        // split into 20-byte MTU chunks like a real ring over ATT
        var i = 0
        while (i < f.size) { val n = minOf(20, f.size - i); onNotify(Uuids.NOTIFY, f.copyOfRange(i, i + n)); i += n }
    }
    private fun le(v: Int, n: Int) = ByteArray(n) { i -> (v shr (8 * i)).toByte() }
    private fun le(v: Long, n: Int) = ByteArray(n) { i -> (v shr (8 * i)).toByte() }

    private fun sleepNight(day: Byte): ByteArray {
        // [day][state hh mm]* points spanning ~23:00→07:00
        val pts = listOf(0 to (23 to 10), 1 to (23 to 40), 2 to (1 to 5), 1 to (2 to 0), 3 to (3 to 30), 1 to (4 to 50), 0 to (6 to 40), 0 to (7 to 15))
        val out = ByteArray(1 + pts.size * 3); out[0] = day
        pts.forEachIndexed { i, (st, hm) -> out[1 + i * 3] = st.toByte(); out[2 + i * 3] = hm.first.toByte(); out[3 + i * 3] = (hm.second + day.toInt() % 10).toByte() }
        return out
    }
    private fun timing(cmd: Int, p: ByteArray) {
        val day = p.getOrElse(0) { 0 }; val page = p.getOrElse(1) { 0 }
        val total = Cmd.TIMING[cmd] ?: 1
        val width = if (cmd == 16 || cmd == 22) 2 else 1
        val n = if (cmd == 16 || cmd == 22) 72 else (if (total == 4) 72 else 144)
        val payload = ByteArray(2 + n * width)
        payload[0] = day; payload[1] = page
        for (i in 0 until n) {
            val v = when (cmd) {
                15 -> 55 + rnd.nextInt(50); 17 -> 94 + rnd.nextInt(5); 47 -> 20 + rnd.nextInt(50)
                16 -> 30 + rnd.nextInt(40); 22 -> 350 + rnd.nextInt(20); else -> 0
            }
            if (width == 2) { payload[2 + i * 2] = (v and 0xFF).toByte(); payload[3 + i * 2] = (v shr 8).toByte() }
            else payload[2 + i] = v.toByte()
        }
        reply(2, cmd, payload)
        // pages after 0 are requested by the TimingCollector; we just answer them
    }
    private fun measured(m: Metric): ByteArray {
        val n = 6; val out = ByteArray(1 + n * 5); out[0] = n.toByte()
        var t = System.currentTimeMillis() / 1000 - n * 3600
        for (i in 0 until n) {
            val v = when (m) { Metric.HEART_RATE -> 60 + rnd.nextInt(40); Metric.SPO2 -> 95 + rnd.nextInt(4); Metric.STRESS -> 20 + rnd.nextInt(40); else -> 0 }
            out[1 + i * 5] = v.toByte()
            val ds = RingTime.toDevice(t * 1000)
            for (k in 0..3) out[2 + i * 5 + k] = (ds shr (8 * k)).toByte()
            t += 3600
        }
        return out
    }
    private fun measuredHrv(): ByteArray {
        val n = 4; val out = ByteArray(1 + n * 6); out[0] = n.toByte()
        var t = System.currentTimeMillis() / 1000 - n * 3600
        for (i in 0 until n) {
            val v = 30 + rnd.nextInt(40)
            out[1 + i * 6] = (v and 0xFF).toByte(); out[2 + i * 6] = (v shr 8).toByte()
            val ds = RingTime.toDevice(t * 1000)
            for (k in 0..3) out[3 + i * 6 + k] = (ds shr (8 * k)).toByte()
            t += 3600
        }
        return out
    }
    override fun read(uuid: UUID) {
        when {
            uuid == Uuids.BATTERY -> onNotify(uuid, byteArrayOf(87))
            uuid == Uuids.STEPS -> emitSteps()
        }
    }
    override fun close() { liveHr = false; onLost("Simulated ring disconnected") }
}
