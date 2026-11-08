package org.fcitx.fcitx5.android.remote

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

object ClipboardLanDiscoveryClient {
    private const val TAG = "ClipboardLanDiscovery"
    private const val APP_ID = "insightkey_clipboard"

    data class Device(
        val deviceId: String,
        val deviceName: String,
        val host: String,
        val httpPort: Int,
        val udpPort: Int,
        val timestamp: Long
    )

    fun discover(
        context: Context,
        timeoutMs: Int = 1200,
        onDiscovered: (Device) -> Unit
    ) {
        Thread {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = timeoutMs
                    val request = JSONObject()
                        .put("type", "discover")
                        .put("app", APP_ID)
                        .put("replyPort", socket.localPort)
                        .put("deviceId", ClipboardSync.deviceId(context))
                        .toString()
                        .toByteArray(StandardCharsets.UTF_8)
                    val target = DatagramPacket(
                        request,
                        request.size,
                        InetAddress.getByName("255.255.255.255"),
                        ClipboardLanDiscoveryServer.DEFAULT_UDP_PORT
                    )
                    socket.send(target)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val buf = ByteArray(2048)
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val payload = packet.data.copyOf(packet.length)
                            .toString(StandardCharsets.UTF_8)
                            .trim()
                        val json = JSONObject(payload)
                        if (json.optString("type") != "discover_ack") continue
                        if (json.optString("app") != APP_ID) continue
                        onDiscovered(
                            Device(
                                deviceId = json.optString("deviceId"),
                                deviceName = json.optString("deviceName"),
                                host = packet.address.hostAddress.orEmpty(),
                                httpPort = json.optInt("httpPort", ClipboardLanDiscoveryServer.DEFAULT_HTTP_PORT),
                                udpPort = json.optInt("udpPort", ClipboardLanDiscoveryServer.DEFAULT_UDP_PORT),
                                timestamp = json.optLong("timestamp", 0L)
                            )
                        )
                    }
                }
            }.onFailure {
                Log.w(TAG, "udp discover failed", it)
            }
        }.apply {
            name = "clipboard-lan-discover-client"
            isDaemon = true
            start()
        }
    }
}
