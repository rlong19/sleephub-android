package com.rlong.sleephub.ring.proto

/**
 * Lean incremental sync (v15). Default: day 0 of every supported table, starting at the page that covers the last
 * successful sync, plus day 1 if it is not stored complete. Full: days 0..[MAX_DAY]. Never requests the
 * permanently-unsupported commands (2/2D, 2/2F, 2/25, 7/00, 7/01, 7/04, 7/0C, 7/0E) — those are probe-only.
 */
data class SyncPlan(val days: List<Int>, val frames: List<ByteArray>, val timingStart: Map<Int, Int>, val actionStart: Int) {
    fun describe() = "days=${days.joinToString(",")} cmds=${frames.size} day0 pages from " +
        timingStart.entries.joinToString(" ") { "2/%02X:p%d".format(it.key, it.value) } + " 2/13:p$actionStart"

    companion object {
        const val MAX_DAY = 6
        /** 5-minute tables: HR 2/0F, HRV 2/10, SpO2 2/11, temp 2/16 (cmd -> pages per day). */
        val TABLES = listOf(15, 16, 17, 22)
        /** Re-read this much before the last sync so a slot that was still filling gets its final value. */
        const val OVERLAP_MS = 15 * 60_000L

        fun timingFirstPage(cmd: Int, lastSyncMs: Long, dayStart0: Long): Int {
            if (lastSyncMs < dayStart0 + OVERLAP_MS) return 0
            val total = Cmd.TIMING[cmd] ?: 1
            val slot = ((lastSyncMs - OVERLAP_MS - dayStart0) / 300_000L).toInt()
            return (slot / (288 / total)).coerceIn(0, total - 1)
        }
        fun actionFirstPage(lastSyncMs: Long, dayStart0: Long): Int {
            if (lastSyncMs < dayStart0 + OVERLAP_MS) return 0
            val min = ((lastSyncMs - OVERLAP_MS - dayStart0) / 60_000L).toInt()
            return (min / (1440 / Actigraphy.PAGES)).coerceIn(0, Actigraphy.PAGES - 1)
        }

        fun build(full: Boolean, nowMs: Long, lastSyncMs: Long, yesterdayDone: Boolean, needIntervals: Boolean, dayStart0: Long): SyncPlan {
            val days = if (full) (0..MAX_DAY).toList() else listOfNotNull(0, if (yesterdayDone) null else 1)
            val last = if (full || lastSyncMs > nowMs) 0L else lastSyncMs
            val tStart = TABLES.associateWith { timingFirstPage(it, last, dayStart0) }
            val aStart = actionFirstPage(last, dayStart0)
            val f = ArrayList<ByteArray>()
            if (needIntervals || full) listOf(6, 7, 8, 21).forEach { f += Frame.build(2, it) }
            for (d in days) {
                f += Cmd.historySleep(d)
                f += Cmd.actionDetails(d, if (d == 0) aStart else 0)
                for (c in TABLES) f += Cmd.timingPage(c, d, if (d == 0) tStart[c]!! else 0)
            }
            f += Cmd.historyHeartRate(); f += Cmd.historyHrv(); f += Cmd.historySpO2(); f += Cmd.historyStress()
            return SyncPlan(days, f, tStart, aStart)
        }
    }
}
