package org.fcitx.fcitx5.android.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.json.JSONObject
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager as FcitxClipboardManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardHttpServer(
    private val context: Context,
    private val port: Int = 51423,
    private val onClientAccess: (() -> Unit)? = null
) {
    private data class IncomingClipboardPayload(
        val content: String,
        val timestamp: Long,
        val sensitive: Boolean
    )

    private val tag = "ClipboardHttpServer"
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val worker: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread {
            runCatching {
                ServerSocket(port).also { serverSocket = it }
                Log.i(tag, "listening on 0.0.0.0:$port")
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    worker.execute { handleClient(socket) }
                }
            }.onFailure {
                Log.e(tag, "server stopped with error", it)
            }.also {
                running.set(false)
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }.apply {
            name = "clipboard-http-server"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            runCatching {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val requestLine = readLine(input)?.trim().orEmpty()
                if (requestLine.isEmpty()) {
                    writeResponse(output, 400, "Bad Request")
                    return
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim().lowercase()
                        val v = line.substring(idx + 1).trim()
                        headers[k] = v
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = if (contentLength > 0) {
                    ByteArray(contentLength).also { bytes ->
                        var offset = 0
                        while (offset < contentLength) {
                            val n = input.read(bytes, offset, contentLength - offset)
                            if (n <= 0) break
                            offset += n
                        }
                    }
                } else {
                    ByteArray(0)
                }

                val routePath = path.substringBefore('?')
                val isClipboardRoute = routePath == "/clipboard" || routePath == "/"
                when {
                    method == "OPTIONS" -> writeResponse(output, 204, "")
                    !isClipboardRoute -> writeResponse(output, 404, "Not Found")
                    method == "GET" -> {
                        onClientAccess?.invoke()
                        val limit = parseLimit(path)
                        val body = buildClipboardJson(limit)
                        writeResponse(output, 200, body, "application/json; charset=utf-8")
                    }
                    method == "POST" -> {
                        onClientAccess?.invoke()
                        val payload = parseIncomingClipboardPayload(
                            bodyBytes.toString(StandardCharsets.UTF_8)
                        )
                        writeClipboardPayload(payload)
                        writeResponse(output, 200, "{\"ok\":true}", "application/json; charset=utf-8")
                    }
                    else -> writeResponse(output, 405, "Method Not Allowed")
                }
            }.onFailure {
                Log.w(tag, "client handling failed", it)
            }
        }
    }

    private fun readClipboardText(): String {
        val systemText = runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return@runCatching ""
            val item = clip.getItemAt(0)
            item.coerceToText(context)?.toString().orEmpty()
        }.getOrDefault("")
        if (systemText.isNotBlank()) return systemText
        return FcitxClipboardManager.lastEntry?.text.orEmpty()
    }

    private fun readClipboardEntries(limit: Int): List<ClipboardEntry> {
        return runBlocking {
            FcitxClipboardManager.recentEntries(limit)
        }
    }

    private fun buildClipboardJson(limit: Int): String {
        val entries = runCatching { readClipboardEntries(limit) }.getOrDefault(emptyList())
        val merged = if (entries.isNotEmpty()) {
            entries
        } else {
            val fallback = readClipboardText()
            if (fallback.isNotBlank()) {
                listOf(
                    ClipboardEntry(
                        id = -1,
                        text = fallback,
                        pinned = false,
                        timestamp = System.currentTimeMillis(),
                        sensitive = false
                    )
                )
            } else {
                emptyList()
            }
        }
        val items = merged.joinToString(separator = ",") { e ->
            """{"id":${e.id},"content":"${jsonEscape(e.text)}","timestamp":${e.timestamp}}"""
        }
        return """{"count":${merged.size},"items":[$items]}"""
    }

    private fun parseLimit(path: String): Int {
        val query = path.substringAfter('?', "")
        if (query.isBlank()) return 20
        val pairs = query.split('&')
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val value = pair.substring(idx + 1)
            if (key == "limit") {
                return value.toIntOrNull()?.coerceIn(1, 200) ?: 20
            }
        }
        return 20
    }

    private fun jsonEscape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        raw.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun writeClipboardText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("remote", text))
    }

    private fun writeClipboardPayload(payload: IncomingClipboardPayload) {
        runBlocking {
            FcitxClipboardManager.importRemoteEntry(
                text = payload.content,
                timestamp = payload.timestamp,
                sensitive = payload.sensitive
            )
        }
    }

    private fun parseIncomingClipboardPayload(raw: String): IncomingClipboardPayload {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return IncomingClipboardPayload("", System.currentTimeMillis(), false)
        }
        return runCatching {
            val obj = JSONObject(trimmed)
            val content = obj.optString("content").ifBlank { obj.optString("text") }.trim()
            IncomingClipboardPayload(
                content = content,
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                sensitive = obj.optBoolean("sensitive", false)
            )
        }.getOrElse {
            IncomingClipboardPayload(trimmed, System.currentTimeMillis(), false)
        }
    }

    private fun writeResponse(
        out: BufferedOutputStream,
        code: Int,
        body: String,
        contentType: String = "text/plain; charset=utf-8"
    ) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            204 -> "204 No Content"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            405 -> "405 Method Not Allowed"
            else -> "$code Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(64)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.UTF_8).trimEnd('\r')
            }
            if (b == '\n'.code) {
                return bytes.toByteArray().toString(StandardCharsets.UTF_8).trimEnd('\r')
            }
            bytes.add(b.toByte())
        }
    }

    private fun List<Byte>.toByteArray(): ByteArray {
        val arr = ByteArray(size)
        for (i in indices) arr[i] = this[i]
        return arr
    }
}
