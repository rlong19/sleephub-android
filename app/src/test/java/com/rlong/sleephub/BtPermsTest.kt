package com.rlong.sleephub

import com.rlong.sleephub.core.BtPerms
import org.junit.Assert.*
import org.junit.Test

class BtPermsTest {
    private val none: (String) -> Boolean = { false }
    private val all: (String) -> Boolean = { true }
    @Test fun api31RevokedConnectIsDetected() {
        assertTrue(BtPerms.needsConnect(31, none)); assertTrue(BtPerms.needsScan(34, none))
        assertEquals(listOf(BtPerms.SCAN, BtPerms.CONNECT), BtPerms.missing(34, none))
    }
    @Test fun onlyConnectRevoked() {
        val g: (String) -> Boolean = { it != BtPerms.CONNECT }
        assertTrue(BtPerms.needsConnect(33, g)); assertFalse(BtPerms.needsScan(33, g))
        assertEquals(listOf(BtPerms.CONNECT), BtPerms.missing(33, g))
    }
    @Test fun grantedNeedsNothing() { assertFalse(BtPerms.needsConnect(34, all)); assertTrue(BtPerms.missing(34, all).isEmpty()) }
    @Test fun preApi31HasNoRuntimeBtPerms() {
        assertFalse(BtPerms.needsConnect(30, none)); assertFalse(BtPerms.needsScan(30, none))
        assertEquals(listOf(BtPerms.FINE_LOCATION), BtPerms.missing(30, none))
    }
}
