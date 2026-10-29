package com.rlong.sleephub.zeo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.PowerManager
import com.rlong.sleephub.core.BtPerms
import com.rlong.sleephub.core.TrafficLogger
import java.io.IOException
import java.util.UUID

interface ZeoTransport { fun write(b: ByteArray); fun close() }

/**
 * Classic-BT RFCOMM link to a Zeo Mobile headband. Runs BOTH roles like the official app:
 *  - server: insecure RFCOMM listener "Zeo Android" / ...d0a5 (headband dials in)
 *  - client: outbound to ...d0a6 with the official fallback chain (insecure UUID → insecure ch2 → secure UUID → secure ch2)
 * Auto-reconnect with backoff until [close] is called.
 */
@SuppressLint("MissingPermission")
class RfcommZeoLink(
    private val ctx: Context,
    private val address: String,
    private val log: TrafficLogger,
    private val onBytes: (ByteArray) -> Unit,
    private val onUp: (String) -> Unit,
    private val onDown: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : ZeoTransport {
    companion object {
        val ANDROID_UUID: UUID = UUID.fromString("56b32a76-479b-43d4-99ff-42d79823d0a5")
        val HEADBAND_UUID: UUID = UUID.fromString("56b32a76-479b-43d4-99ff-42d79823d0a6")
        const val SERVICE_NAME = "Zeo Android"
        const val FALLBACK_CHANNEL = 2
        const val ZEO_OUI = "4C:98:EF:"
    }
    @Volatile private var closed = false
    @Volatile private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private val lock = Object()
    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    init {
        Thread({ guarded("server", ::serverLoop) }, "zeo-server").apply { isDaemon = true }.start()
        Thread({ guarded("client", ::clientLoop) }, "zeo-client").apply { isDaemon = true }.start()
    }

    /** Thread body wrapper: a SecurityException (permission revoked) or any other throwable never kills the process;
     *  the loop is restarted after a pause until [close]. */
    private fun guarded(name: String, body: () -> Unit) {
        while (!closed) {
            try { body(); return }
            catch (e: SecurityException) { try { log.add("SYS", name, null, "SecurityException: ${e.message}") } catch (_: Throwable) {}; try { onStatus(BtPerms.MSG) } catch (_: Throwable) {}; sleep(10_000) }
            catch (e: Throwable) { try { log.add("SYS", name, null, "crash ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}; sleep(5000) }
        }
    }

    private fun serverLoop() {
        while (!closed) {
            if (!BtPerms.canConnect(ctx)) { onStatus(BtPerms.MSG); sleep(10_000); continue }
            try {
                val ss = adapter?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, ANDROID_UUID)
                if (ss == null) { sleep(5000); continue }
                server = ss
                log.add("SYS", "server", null, "listening '$SERVICE_NAME' $ANDROID_UUID")
                while (!closed) {
                    val s = ss.accept() ?: continue
                    val from = s.remoteDevice?.address ?: "?"
                    log.add("SYS", "accept", null, "inbound from $from")
                    if (!from.equals(address, true)) { log.add("SYS", "accept", null, "ignored: not selected headband $address"); try { s.close() } catch (_: Exception) {}; continue }
                    adopt(s, "inbound (headband dialled in)")
                }
            } catch (e: Exception) {
                if (!closed) { log.add("SYS", "server", null, "error ${e.javaClass.simpleName}: ${e.message}"); sleep(5000) }
            } finally { try { server?.close() } catch (_: Exception) {}; server = null }
        }
    }

    /** Persistent reconnect: never gives up while this link is open (= Zeo enabled). Backoff 5 s → 60 s cap, reset on success.
     *  A partial wakelock is held only for the duration of each connect attempt (bounded 30 s) so it survives screen-off/Doze. */
    private fun clientLoop() {
        var backoff = 5000L; var attempt = 0
        while (!closed) {
            if (socket != null) { backoff = 5000L; attempt = 0; sleep(1000); continue }
            if (!BtPerms.canConnect(ctx)) { onStatus(BtPerms.MSG); sleep(10_000); continue }
            val dev = try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null }
            if (dev == null || adapter?.isEnabled != true) { onStatus("Bluetooth off / device unknown — waiting"); sleep(5000); continue }
            if ((try { dev.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }) != BluetoothDevice.BOND_BONDED) onStatus("Headband not bonded — pair it in Android Bluetooth settings (PIN 0000)")
            try { adapter?.cancelDiscovery() } catch (_: Exception) {}
            attempt++
            log.add("SYS", "reconnect", null, "attempt #$attempt to $address (backoff ${backoff / 1000}s)")
            onStatus("Connecting to $address … attempt #$attempt (also listening for inbound)")
            val wl = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleephub:zeo-reconnect").apply { setReferenceCounted(false); acquire(30_000L) } } catch (_: Exception) { null }
            val s = try { tryConnect(dev) } catch (e: SecurityException) { onStatus(BtPerms.MSG); null } finally { try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {} }
            if (s != null) { log.add("SYS", "reconnect", null, "attempt #$attempt OK — backoff reset"); backoff = 5000L; attempt = 0; adopt(s, "outbound") }
            else if (socket == null) {
                log.add("SYS", "reconnect", null, "attempt #$attempt failed — next in ${backoff / 1000}s")
                onStatus("Not reachable — retry #${attempt + 1} in ${backoff / 1000}s (listening for inbound)")
                sleepUnlessUp(backoff); backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }
    /** Sleep, but wake early if the headband dialled in meanwhile. */
    private fun sleepUnlessUp(ms: Long) { var left = ms; while (left > 0 && !closed && socket == null) { sleep(minOf(1000L, left)); left -= 1000 } }

    private fun tryConnect(dev: BluetoothDevice): BluetoothSocket? {
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "insecure UUID d0a6" to { dev.createInsecureRfcommSocketToServiceRecord(HEADBAND_UUID) },
            "insecure ch$FALLBACK_CHANNEL (reflection)" to { dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, FALLBACK_CHANNEL) as BluetoothSocket },
            "secure UUID d0a6" to { dev.createRfcommSocketToServiceRecord(HEADBAND_UUID) },
            "secure ch$FALLBACK_CHANNEL (reflection)" to { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, FALLBACK_CHANNEL) as BluetoothSocket },
        )
        for ((name, make) in attempts) {
            if (closed || socket != null) return null
            var s: BluetoothSocket? = null
            try { s = make(); s.connect(); log.add("SYS", "connect", null, "OK via $name"); return s }
            catch (e: Throwable) { log.add("SYS", "connect", null, "fail via $name: ${e.javaClass.simpleName} ${e.message}"); try { s?.close() } catch (_: Exception) {} }
        }
        return null
    }

    private fun adopt(s: BluetoothSocket, how: String) {
        synchronized(lock) {
            if (closed) { try { s.close() } catch (_: Exception) {}; return }
            try { socket?.close() } catch (_: Exception) {}
            socket = s
        }
        onUp(how)
        Thread({ try { readLoop(s) } catch (e: Throwable) { try { log.add("SYS", "reader", null, "crash ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {} } }, "zeo-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop(s: BluetoothSocket) {
        val buf = ByteArray(2048)
        try {
            val ins = s.inputStream
            while (!closed) {
                val n = ins.read(buf)
                if (n < 0) throw IOException("EOF")
                if (n > 0) onBytes(buf.copyOf(n))
            }
        } catch (e: Exception) {
            val mine = synchronized(lock) { if (socket === s) { socket = null; true } else false }
            try { s.close() } catch (_: Exception) {}
            if (mine && !closed) onDown("link lost: ${e.message}")
        }
    }

    override fun write(b: ByteArray) {
        val s = socket ?: run { log.add("SYS", "writeFail", b, "not connected"); return }
        try { s.outputStream.write(b); s.outputStream.flush() } catch (e: Exception) { log.add("SYS", "writeFail", b, e.message ?: "") }
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}; socket = null
        try { server?.close() } catch (_: Exception) {}
    }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}
