package com.rlong.sleephub.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime Bluetooth permission gate. On API 31+ BLUETOOTH_CONNECT/SCAN are runtime permissions that can be revoked
 *  at any time; every BT entry point checks here first so a revoked grant degrades to "waiting" instead of crashing. */
object BtPerms {
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val MSG = "Bluetooth permission missing — open SleepHub to grant \"Nearby devices\""

    /** Pure logic (unit-tested): which BT permissions are missing for [sdk] given a grant check. */
    fun missing(sdk: Int, granted: (String) -> Boolean): List<String> =
        (if (sdk >= 31) listOf(SCAN, CONNECT) else listOf(FINE_LOCATION)).filterNot(granted)
    fun needsConnect(sdk: Int, granted: (String) -> Boolean) = sdk >= 31 && !granted(CONNECT)
    fun needsScan(sdk: Int, granted: (String) -> Boolean) = sdk >= 31 && !granted(SCAN)

    private fun g(ctx: Context): (String) -> Boolean = { p ->
        try { ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false } }
    fun canConnect(ctx: Context) = !needsConnect(Build.VERSION.SDK_INT, g(ctx))
    fun canScan(ctx: Context) = !needsScan(Build.VERSION.SDK_INT, g(ctx))
    fun canAll(ctx: Context) = canConnect(ctx) && canScan(ctx)
    fun missing(ctx: Context) = missing(Build.VERSION.SDK_INT, g(ctx))
}
