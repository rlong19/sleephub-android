package com.rlong.sleephub.ring.proto

/** What reply completes a command: same group/cmd, and the payload must start with [echo] (day/page/id). */
data class Expect(val group: Int, val cmd: Int, val echo: ByteArray = ByteArray(0)) {
    fun matches(frame: ByteArray): Boolean {
        if (frame.size < 6 || u8(frame, 4) != group || u8(frame, 5) != cmd) return false
        if (frame.size < 6 + echo.size) return false
        return echo.indices.all { frame[6 + it] == echo[it] }
    }
    fun label() = "$group/${"%02X".format(cmd)}" + if (echo.isNotEmpty()) " " + echo.joinToString("/") { (it.toInt() and 0xFF).toString() } else ""

    companion object {
        /** Reply rule for an outgoing frame, from the SDK listeners (g1.a). null = fire-and-forget (writes, measure starts). */
        fun forFrame(f: ByteArray): Expect? {
            if (f.size < 6) return null
            val g = u8(f, 4); val c = u8(f, 5); val p = f.copyOfRange(6, f.size)
            return when (g) {
                2 -> when (c) {
                    13, 14, 18 -> Expect(g, c, p.copyOfRange(0, minOf(1, p.size)))                 // [day]
                    15, 16, 17, 19, 22, 47 -> Expect(g, c, p.copyOfRange(0, minOf(2, p.size)))     // [day, page]
                    else -> Expect(g, c)
                }
                3 -> if (c in listOf(3, 6)) Expect(g, c) else null
                5 -> if (c == 2) Expect(g, c) else null
                7 -> when (c) {
                    5, 6 -> Expect(g, c, p.copyOfRange(0, minOf(1, p.size)))                       // [id]
                    14 -> Expect(g, c, p.copyOfRange(0, minOf(2, p.size)))                         // [day, page]
                    0, 1, 2, 4, 7, 8, 10, 12 -> Expect(g, c)
                    else -> null
                }
                else -> null
            }
        }
        /** (group, cmd, day) for paged/day tables, used to stop paging after a day times out twice. */
        fun dayKey(e: Expect): Triple<Int, Int, Int>? = if (e.echo.isNotEmpty() && !(e.group == 7 && e.cmd in listOf(5, 6))) Triple(e.group, e.cmd, e.echo[0].toInt() and 0xFF) else null
    }
}

interface Scheduler { fun post(delayMs: Long, r: Runnable); fun cancel(r: Runnable) }

enum class ReplyKind { COMPLETED, LATE, DUPLICATE, UNSOLICITED }

/**
 * The single ring command queue. Up to [window] commands in flight, matched to replies by group/cmd + day/page echo;
 * [timeoutMs] per attempt, one retry, then TIMEOUT. [gapMs] between any two sends. A `serial` command is sent only when
 * nothing else is in flight and blocks others until it finishes. A reply for an already-completed command is a
 * DUPLICATE (caller must ignore it); a reply for a command that already timed out twice is LATE (handled once).
 * Not thread-safe: call from one thread.
 */
class CommandQueue(
    private val sched: Scheduler,
    private val transmit: (ByteArray, String) -> Unit,
    private val log: (String) -> Unit,
    val timeoutMs: Long = 20_000, val gapMs: Long = 80, val window: Int = 4,
) {
    inner class Item(val frame: ByteArray, val note: String, val expect: Expect?, val serial: Boolean) {
        var attempts = 0
        val timer = Runnable { onTimeoutFired(this) }
    }
    private val pending = ArrayDeque<Item>()
    val inFlight = ArrayList<Item>()
    private val completed = HashSet<String>()
    private val timedOut = HashSet<String>()
    val outstanding get() = inFlight.size
    var maxOutstanding = 0; private set
    var sent = 0; private set
    var timeouts = 0; private set
    var retries = 0; private set
    var enabled = true
    private var gapUntilNext = false
    val size get() = pending.size + inFlight.size
    var onTimeout: ((Item) -> Unit)? = null
    var onProgress: (() -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    private val next = Runnable { gapUntilNext = false; pump() }

    fun enqueue(frame: ByteArray, note: String = "", front: Boolean = false, serial: Boolean = false) {
        val it = Item(frame, note, Expect.forFrame(frame), serial)
        if (front) pending.addFirst(it) else pending.addLast(it)
        pump()
    }
    fun clear() { pending.clear(); inFlight.forEach { sched.cancel(it.timer) }; inFlight.clear(); sched.cancel(next); gapUntilNext = false }
    /** Link lost: keep everything; in-flight commands go back to the front, to be resent after reconnect. */
    fun suspend() {
        enabled = false
        inFlight.forEach { sched.cancel(it.timer); it.attempts = 0 }
        for (i in inFlight.indices.reversed()) pending.addFirst(inFlight[i])
        inFlight.clear(); sched.cancel(next); gapUntilNext = false
    }
    fun resume() { enabled = true; pump() }
    fun pendingFrames(): List<ByteArray> = pending.map { it.frame }

    fun pump() {
        if (!enabled || gapUntilNext || pending.isEmpty()) return
        if (inFlight.size >= window || inFlight.any { it.serial }) return
        if (pending.first().serial && inFlight.isNotEmpty()) return
        val it = pending.removeFirst()
        it.expect?.let { e -> completed.remove(e.label()); timedOut.remove(e.label()) }
        transmit(it)
    }
    private fun transmit(it: Item) {
        it.attempts++; sent++
        if (it.expect != null) { inFlight += it; sched.post(timeoutMs, it.timer) }
        if (inFlight.size > maxOutstanding) maxOutstanding = inFlight.size
        transmit(it.frame, it.note)
        onProgress?.invoke()
        gapUntilNext = true; sched.post(gapMs, next)
    }
    /** Feed every complete reply frame. */
    fun onReply(frame: ByteArray): ReplyKind {
        val it = inFlight.firstOrNull { i -> i.expect!!.matches(frame) }
        if (it != null) {
            sched.cancel(it.timer); inFlight.remove(it); completed += it.expect!!.label()
            afterFinish(); return ReplyKind.COMPLETED
        }
        val label = completed.firstOrNull { l -> labelMatches(l, frame) }
        if (label != null) return ReplyKind.DUPLICATE
        val late = timedOut.firstOrNull { l -> labelMatches(l, frame) }
        if (late != null) { timedOut.remove(late); completed += late; log("LATE $late"); return ReplyKind.LATE }
        return ReplyKind.UNSOLICITED
    }
    private fun labelMatches(label: String, frame: ByteArray): Boolean {
        if (frame.size < 6) return false
        val e = Expect.forFrame(Frame.build(u8(frame, 4), u8(frame, 5), frame.copyOfRange(6, minOf(frame.size, 8)))) ?: return false
        return e.label() == label
    }
    private fun onTimeoutFired(it: Item) {
        if (it !in inFlight) return
        val e = it.expect!!
        if (it.attempts < 2) { retries++; log("RETRY ${e.label()}"); inFlight.remove(it); transmitRetry(it); return }
        inFlight.remove(it); timeouts++; log("TIMEOUT ${e.label()}"); timedOut += e.label()
        Expect.dayKey(e)?.let { (g, c, d) ->
            val drop: (Item) -> Boolean = { p -> p.expect?.let { Expect.dayKey(it) }?.let { k -> k.first == g && k.second == c && k.third > d } == true }
            val n = pending.count(drop)
            if (n > 0) { pending.removeAll(drop); log("STOP $g/${"%02X".format(c)} after day $d ($n queued requests dropped)") }
        }
        onTimeout?.invoke(it)
        afterFinish()
    }
    /** Retries jump the queue but still respect the gap. */
    private fun transmitRetry(it: Item) { pending.addFirst(it); pump() }
    private fun afterFinish() { onProgress?.invoke(); if (size == 0) onIdle?.invoke(); pump() }
}
