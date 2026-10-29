package com.rlong.sleephub.serial

import com.rlong.sleephub.core.DataSource
import com.rlong.sleephub.core.Timeline
import com.rlong.sleephub.core.TrafficLogger

/**
 * PLACEHOLDER for the bedside Zeo Sleep Manager raw serial stream (38400 8N1, "A4" frames —
 * see de.rho.zeolab / zeolab-android `zeo` parser). Not enabled in this build.
 *
 * To wire it up later:
 *  1. Add a USB-serial transport (e.g. usb-serial-for-android, FTDI/CP210x/PL2303 at 38400 baud) or a BT-serial bridge.
 *  2. Copy de.rho.zeolab.zeo parser (A4 frame sync, checksum, waveform / frequency bins / sleep stage / SQI)
 *     into this package and feed it the byte stream in [onBytes].
 *  3. For every decoded event call timeline.add("sleepmanager", key, value) — e.g. "stage", "bins.delta", "sqi", "impedance".
 *  4. Add an instance to App.extraSources; SleepHubService starts/stops every registered DataSource.
 */
class SleepManagerSource : DataSource {
    override val id = "sleepmanager"
    override val label = "Zeo Sleep Manager (serial) — not implemented yet"
    override var status = "disabled"; private set
    val log = TrafficLogger("sleepmanager")
    private var timeline: Timeline? = null
    override fun start(timeline: Timeline) { this.timeline = timeline; status = "disabled (hook only)" }
    override fun stop() { timeline = null; status = "stopped" }
    /** Feed raw serial bytes here once a transport exists. */
    fun onBytes(b: ByteArray) { log.add("RX", "serial", b) /* TODO: ZeoLab A4 parser → timeline */ }
}
