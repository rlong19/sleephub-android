package com.rlong.sleephub.ring

import com.rlong.sleephub.ring.proto.*
import org.junit.Assert.*
import org.junit.Test

/** Virtual-time scheduler. */
class FakeSched : Scheduler {
    var now = 0L
    private val tasks = ArrayList<Pair<Long, Runnable>>()
    override fun post(delayMs: Long, r: Runnable) { tasks += (now + delayMs) to r }
    override fun cancel(r: Runnable) { tasks.removeAll { it.second === r } }
    fun advance(ms: Long) { val end = now + ms
        while (true) { val t = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break; tasks.remove(t); now = t.first; t.second.run() }
        now = end }
}

class CommandQueueTest {
    private val sched = FakeSched()
    private val sent = ArrayList<Pair<Long, ByteArray>>()
    private val logs = ArrayList<String>()
    private val q = CommandQueue(sched, { f, _ -> sent += sched.now to f }, { logs += it })
    private fun f(g: Int, c: Int, vararg p: Int) = Frame.build(g, c, ByteArray(p.size) { p[it].toByte() })
    private fun echo(frame: ByteArray) = Frame.build(frame[4].toInt(), frame[5].toInt(), frame.copyOfRange(6, frame.size))

    @Test fun neverMoreThanWindowAndSerialAlone() {
        for (d in 0..5) { q.enqueue(Cmd.timingPage(15, d, 0)); q.enqueue(Cmd.historySleep(d)) }
        q.enqueue(Frame.build(2, 45), serial = true); q.enqueue(Frame.build(7, 0), serial = true); q.enqueue(Cmd.timingPage(16, 0, 0))
        var guard = 0
        while (q.size > 0 && guard++ < 2000) {
            assertTrue(q.outstanding <= 4)
            if (q.inFlight.any { it.serial }) assertEquals(1, q.outstanding)
            if (guard % 5 == 0) q.inFlight.firstOrNull()?.let { q.onReply(echo(it.frame)) }
            sched.advance(100)
        }
        assertEquals(0, q.size); assertEquals(4, q.maxOutstanding)
        sent.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b.first - a.first}", b.first - a.first >= 80) }
    }

    @Test fun pipelinesFourThenWaits() {
        for (d in 0..5) q.enqueue(Cmd.timingPage(15, d, 0))
        sched.advance(1000); assertEquals(4, sent.size)
        assertEquals(ReplyKind.COMPLETED, q.onReply(f(2, 15, 2, 0, 70)))     // out of order is fine
        sched.advance(80); assertEquals(5, sent.size)
        assertEquals(ReplyKind.UNSOLICITED, q.onReply(f(2, 16, 0, 0)))
    }

    @Test fun lateReplyCompletesAndDuplicateIgnored() {
        q.enqueue(Cmd.timingPage(22, 0, 0))
        sched.advance(19_000); assertEquals(1, sent.size)
        assertEquals(ReplyKind.COMPLETED, q.onReply(f(2, 22, 0, 0, 1)))       // 19 s late: still completes, no retry
        assertEquals(ReplyKind.DUPLICATE, q.onReply(f(2, 22, 0, 0, 1)))       // second echo is ignored
        sched.advance(30_000); assertTrue(logs.none { it.startsWith("RETRY") || it.startsWith("TIMEOUT") })
        // re-requesting the same page later is a fresh command, not a duplicate
        q.enqueue(Cmd.timingPage(22, 0, 0)); assertEquals(ReplyKind.COMPLETED, q.onReply(f(2, 22, 0, 0, 1)))
    }

    @Test fun retryEchoAfterCompletionIsDuplicate() {
        q.enqueue(Cmd.timingPage(22, 0, 1))
        sched.advance(20_000); assertEquals(listOf("RETRY 2/16 0/1"), logs); assertEquals(2, sent.size)
        assertEquals(ReplyKind.COMPLETED, q.onReply(f(2, 22, 0, 1)))
        assertEquals(ReplyKind.DUPLICATE, q.onReply(f(2, 22, 0, 1)))
    }

    @Test fun retryOnceThenTimeoutStopPagingAndLate() {
        for (d in 0..4) q.enqueue(Cmd.timingPage(47, d, 0), serial = d == 0)
        q.enqueue(Frame.build(7, 12))
        sched.advance(20_000); assertEquals("RETRY 2/2F 0/0", logs[0]); assertEquals(1, q.outstanding)
        sched.advance(20_000)
        assertEquals("TIMEOUT 2/2F 0/0", logs[1]); assertTrue(logs[2].startsWith("STOP 2/2F after day 0 (4"))
        sched.advance(80); assertArrayEquals(Frame.build(7, 12), sent.last().second)
        assertEquals(ReplyKind.LATE, q.onReply(f(2, 47, 0, 0, 5)))            // handled once
        assertEquals(ReplyKind.DUPLICATE, q.onReply(f(2, 47, 0, 0, 5)))
    }

    @Test fun suspendResumeKeepsQueue() {
        for (d in 0..5) q.enqueue(Cmd.timingPage(15, d, 0))
        sched.advance(1000); assertEquals(4, sent.size)
        q.suspend(); assertEquals(6, q.size); assertEquals(0, q.outstanding)
        sched.advance(60_000); assertEquals(4, sent.size)                     // nothing sent, no timeouts while down
        q.resume(); sched.advance(1000)
        assertArrayEquals(Cmd.timingPage(15, 0, 0), sent[4].second)           // in-flight ones resent first
        assertTrue(logs.none { it.startsWith("TIMEOUT") })
    }

    @Test fun fireAndForgetOnlyWaitsTheGap() {
        q.enqueue(AutoMeasure.write(Metric.SPO2, 15)); q.enqueue(AutoMeasure.query(Metric.SPO2))
        assertEquals(1, sent.size); sched.advance(80); assertEquals(2, sent.size)
        assertEquals(ReplyKind.COMPLETED, q.onReply(f(2, 8, 3))); assertEquals(0, q.size)
    }

    @Test fun expectRules() {
        assertEquals(Expect(2, 19, byteArrayOf(3, 5)).label(), Expect.forFrame(Cmd.actionDetails(3, 5))!!.label())
        assertEquals("7/0E 0/0", Expect.forFrame(Cmd.timingPage(Cmd.ENERGY_TABLE, 0, 0))!!.label())
        assertEquals("2/25", Expect.forFrame(Frame.build(2, 37))!!.label())
        assertNull(Expect.forFrame(Cmd.measureSpO2(true))); assertNull(Expect.forFrame(Cmd.syncTime()))
        assertNull(Expect.dayKey(Expect.forFrame(Cmd.sleepDetails(3))!!))
    }
}
