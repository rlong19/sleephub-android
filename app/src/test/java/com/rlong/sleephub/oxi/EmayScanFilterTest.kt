package com.rlong.sleephub.oxi

import org.junit.Assert.*
import org.junit.Test

class EmayScanFilterTest {
    @Test fun officialPrefixesMatch() {
        listOf("SleepO2", "SleepO2-2 1234", "EMAY Oximeter", "EMAY BT Oximeter", "EM90-01", "EMO-100", "EM60", "EMO-80", "sleepo2 ab").forEach {
            assertTrue(it, EmayProtocol.isEmayName(it)) }
    }
    @Test fun eitherNameSourceCounts() {
        assertTrue(EmayProtocol.isEmayName(null, "SleepO2"))
        assertTrue(EmayProtocol.isEmayName("Unknown", "EMO-100"))
    }
    @Test fun othersRejected() {
        listOf(null, "", "Zeo", "R02_1234", "Pixel Buds").forEach { assertFalse("$it", EmayProtocol.isEmayName(it)) }
    }
}
