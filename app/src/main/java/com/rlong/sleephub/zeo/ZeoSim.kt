package com.rlong.sleephub.zeo

import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEMO headband: speaks real HMSG frames (so the real parser is exercised) with synthetic data.
 * One 30-s epoch is simulated every 2 s. Off by default; everything it produces is labelled DEMO.
 */
class ZeoSim(private val onBytes: (ByteArray) -> Unit, onUp: (String) -> Unit) : ZeoTransport {
    private val h = Handler(Looper.getMainLooper())
    private var closed = false
    private val start = System.currentTimeMillis() / 1000
    private val epochs = ArrayList<Int>()
    private val tick = object : Runnable { override fun run() {
        if (closed) return
        epochs += nextStage()
        if (epochs.size % 10 == 0) emit(Hmsg.build(Hmsg.T.STATE_CHANGE, byteArrayOf(15, 0, 0, 0)))
        h.postDelayed(this, 2000)
    } }
    init { h.postDelayed({ onUp("DEMO simulated headband"); emit(starting(false)) }, 300); h.postDelayed(tick, 2000) }

    private fun nextStage(): Int {
        val n = epochs.size
        if (n < 20) return 1
        val cyc = (n - 20) % 180  // 90-min cycle
        return when { cyc < 30 -> 3; cyc < 80 -> if (n < 400) 4 else 3; cyc < 140 -> 3; cyc < 175 -> 2; else -> 1 }
    }
    private fun emit(f: ByteArray) { if (!closed) h.post { onBytes(f) } }
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun str(bb: ByteBuffer, s: String, n: Int) { val a = ByteArray(n); s.toByteArray().copyInto(a, endIndex = minOf(n - 1, s.length)); bb.put(a) }
    private fun starting(query: Boolean): ByteArray {
        val b = le(104); str(b, "Zeo DEMO", 20); b.put(ByteArray(6)); b.putShort(1); b.putShort(2); b.putShort(3)
        b.putInt(0x0200); str(b, "DEMO", 12); str(b, "DEMO00000000", 16); b.putInt(0x02030007)
        b.putInt(0xEF001EE5.toInt()); b.putInt(0x4C98); str(b, "", 20); b.putInt(0); b.put(1); b.put(if (query) 1 else 0); b.putShort(3)
        return Hmsg.build(Hmsg.T.STARTING, b.array())
    }
    private fun state(): ByteArray {
        val b = le(16); b.put(0); b.put(0); b.put(1 /*demoMode*/); b.put(0); b.put(1); b.put(0); b.put(1); b.put(1)
        b.put(180.toByte()); b.put(3); b.put(0); b.put(2); b.putInt(epochs.size * 30)
        return Hmsg.build(Hmsg.T.STATE, b.array())
    }
    private fun sleep(): ByteArray {
        val b = le(1144)
        val c = epochs.groupingBy { it }.eachCount()
        val min = { s: Int -> (c[s] ?: 0) / 2 }
        b.putInt(start.toInt()); b.putShort(2); b.putShort(min(4).toShort()); b.putShort(min(3).toShort()); b.putShort(min(2).toShort()); b.putShort(min(1).toShort())
        b.putShort(10); b.putShort((min(2) + min(3) + min(4)).toShort()); b.putShort(((min(2) + min(3) + min(4)) / 6).toShort())
        b.put(1); b.put(1); b.put(180.toByte()); b.put(0)
        b.putInt(0); for (s in 0..4) b.putShort((c[s] ?: 0).toShort()); b.putShort(2); b.putShort(epochs.size.toShort()); b.putShort(epochs.count { it > 1 }.toShort())
        b.putShort(20); b.putShort(0); b.putInt(0)
        val disp = epochs.chunked(10).map { ch -> ch.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key }.take(192)
        b.putInt(disp.size); b.putInt(0); b.put(ZeoParser.encodeHyp(disp, 96)); b.putInt(start.toInt())
        b.put(0); b.put(ByteArray(3)); b.putInt(0); b.putShort(0); b.putShort(0); b.putInt(start.toInt()); b.put(1); b.put(ByteArray(3))
        val base = epochs.take(1920); b.putInt(base.size); b.put(ZeoParser.encodeHyp(base, 960))
        return Hmsg.build(Hmsg.T.SLEEP, b.array())
    }
    override fun write(b: ByteArray) {
        val frames = Hmsg.Assembler().feed(b)
        for (f in frames) when (f.type) {
            Hmsg.T.NULL -> emit(starting(true))
            Hmsg.T.COMMAND -> when (f.body[0].toInt()) { 6 -> emit(state()); 8 -> emit(sleep()) }
            Hmsg.T.TIME_QUERY -> { val now = System.currentTimeMillis(); val r = le(12); r.putInt((now / 1000).toInt()); r.putInt((now % 1000).toInt()); r.put(0); r.put(0); r.put(f.seq.toByte()); r.put(0); emit(Hmsg.build(Hmsg.T.TIME_REPORT, r.array())) }
            Hmsg.T.TEST_LED, Hmsg.T.WAKE_WINDOW -> emit(Hmsg.build(Hmsg.T.ACK, byteArrayOf(f.seq.toByte(), 0, 0, 0)))
        }
    }
    override fun close() { closed = true; h.removeCallbacksAndMessages(null) }
}
