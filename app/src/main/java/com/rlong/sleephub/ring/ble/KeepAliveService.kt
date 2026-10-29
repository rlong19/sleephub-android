package com.rlong.sleephub.ring.ble

import android.content.Context
import com.rlong.sleephub.service.SleepHubService

/**
 * v18's KeepAliveService API, mapped onto SleepHub's single foreground service (ring BLE + Zeo RFCOMM share it).
 * stop() only stops the service when the Zeo link is not active either.
 */
object KeepAliveService {
    val running get() = SleepHubService.running
    fun start(ctx: Context) = SleepHubService.start(ctx)
    fun stop(ctx: Context) = SleepHubService.releaseRing(ctx)
    fun refresh(ctx: Context) = SleepHubService.notifyRefresh(ctx)
}
