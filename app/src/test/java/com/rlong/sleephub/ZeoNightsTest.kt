package com.rlong.sleephub

import com.rlong.sleephub.zeo.ZStage
import com.rlong.sleephub.zeo.ZeoNights
import org.junit.Assert.assertEquals
import org.junit.Test

class ZeoNightsTest {
    @Test fun segmentsMergeAndMapStages() {
        val e = listOf(ZStage.WAKE, ZStage.WAKE, ZStage.LIGHT, ZStage.UNDEFINED, ZStage.DEEP, ZStage.REM)
        val s = ZeoNights.segments(1000L, e, 30_000L)
        assertEquals(listOf(0, 1, 4, 2, 3), s.map { it.stage })
        assertEquals(1000L to 61_000L, s[0].start to s[0].end)
        assertEquals(91_000L to 121_000L, s[2].start to s[2].end)
        assertEquals(listOf(4), ZeoNights.segments(0L, List(63) { ZStage.UNDEFINED }, 30_000L).map { it.stage })
    }
    @Test fun encodeRoundTrip() {
        val e = listOf(ZStage.REM, ZStage.DEEP, ZStage.UNDEFINED, ZStage.LIGHT, ZStage.WAKE)
        assertEquals(e, ZeoNights.decode(ZeoNights.encode(e)))
    }
}
