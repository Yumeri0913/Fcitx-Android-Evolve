package org.fcitx.fcitx5.android.remote

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardLanPairingBroadcaster(
    private val context: Context,
    private val udpPort: Int = ClipboardLanDiscoveryServer.DEFAULT_UDP_PORT,
    private val httpPort: Int = ClipboardLanDiscoveryServer.DEFAULT_HTTP_PORT
) {
    private val tag = "ClipboardLanPairing"
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val target = InetAddress.getByName("255.255.255.255")
                    while (running.get()) {
                        val payload = buildPayload().toByteArray(StandardCharsets.UTF_8)
                        val packet = DatagramPacket(payload, payload.size, target, udpPort)
                        socket.send(packet)
                        Thread.sleep(BROADCAST_INTERVAL_MS)
                    }
                }
            }.onFailure {
                if (running.get()) {
                    Log.e(tag, "pairing broadcast stopped with error", it)
                }
            }.also {
                running.set(false)
            }
        }.apply {
            name = "clipboard-lan-pairing"
            isDaemon = true
            start()
        }
        Log.i(tag, "pairing broadcast started on udp:$udpPort")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(tag, "pairing broadcast stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun buildPayload(): String {
        return JSONObject()
            .put("type", "discover_ack")
            .put("app", "insightkey_clipboard")
            .put("deviceId", ClipboardSync.deviceId(context))
            .put("deviceName", deviceName())
            .put("httpPort", httpPort)
            .put("udpPort", udpPort)
            .put("platform", "android")
            .put("timestamp", System.currentTimeMillis())
            .toString()
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
            Build.DEVICE.orEmpty().ifBlank { "Android" }
        }
    }

    companion object {
        private const val BROADCAST_INTERVAL_MS = 1000L
    }
}
