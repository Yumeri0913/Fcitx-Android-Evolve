package org.fcitx.fcitx5.android.remote

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardLanDiscoveryServer(
    private val context: Context,
    private val udpPort: Int = DEFAULT_UDP_PORT,
    private val httpPort: Int = DEFAULT_HTTP_PORT
) {
    private val tag = "ClipboardLanDiscovery"
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var listenThread: Thread? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        listenThread = Thread {
            runCatching {
                DatagramSocket(udpPort, InetAddress.getByName("0.0.0.0")).also {
                    it.broadcast = true
                    socket = it
                }
                Log.i(tag, "udp discovery listening on 0.0.0.0:$udpPort")
                while (running.get()) {
                    val buf = ByteArray(2048)
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet) ?: break
                    worker.execute { handlePacket(packet) }
                }
            }.onFailure {
                if (running.get()) {
                    Log.e(tag, "udp discovery stopped with error", it)
                }
            }.also {
                running.set(false)
                runCatching { socket?.close() }
                socket = null
            }
        }.apply {
            name = "clipboard-lan-discovery"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        worker.shutdownNow()
    }

    private fun handlePacket(packet: DatagramPacket) {
        val payload = packet.data.copyOf(packet.length).toString(StandardCharsets.UTF_8).trim()
        if (payload.isBlank()) return
        runCatching {
            val json = JSONObject(payload)
            if (json.optString("type") != TYPE_DISCOVER) return
            if (json.optString("app") != APP_ID) return
            val replyPort = json.optInt("replyPort", packet.port).takeIf { it in 1..65535 } ?: packet.port
            val response = JSONObject()
                .put("type", TYPE_DISCOVER_ACK)
                .put("app", APP_ID)
                .put("deviceId", ClipboardSyncService.deviceId(context))
                .put("deviceName", deviceName())
                .put("httpPort", httpPort)
                .put("udpPort", udpPort)
                .put("version", 1)
                .put("timestamp", System.currentTimeMillis())
                .toString()
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            val reply = DatagramPacket(bytes, bytes.size, packet.address, replyPort)
            socket?.send(reply)
            Log.d(tag, "discover ack sent to ${packet.address.hostAddress}:$replyPort")
        }.onFailure {
            Log.w(tag, "ignore invalid discovery packet from ${packet.address.hostAddress}:${packet.port}", it)
        }
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
            Build.DEVICE.orEmpty().ifBlank { "Android" }
        }
    }

    companion object {
        const val DEFAULT_UDP_PORT = 39393
        const val DEFAULT_HTTP_PORT = 51423
        private const val APP_ID = "insightkey_clipboard"
        private const val TYPE_DISCOVER = "discover"
        private const val TYPE_DISCOVER_ACK = "discover_ack"
    }
}
