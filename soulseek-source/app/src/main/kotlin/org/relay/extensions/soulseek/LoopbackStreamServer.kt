package org.relay.extensions.soulseek

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 server on 127.0.0.1 that renders in-memory Soulseek transfers as byte ranges
 * so Relay's player can stream a peer download. Binds to loopback only.
 */
class LoopbackStreamServer {
    private var server: ServerSocket? = null
    private val transfers = ConcurrentHashMap<Int, DownloadTransfer>()
    private val handlerSlots = Semaphore(MAX_CONCURRENT_STREAMS)
    private val threadCounter = AtomicInteger(0)

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({ runAcceptLoop(socket) }, "relay-slsk-http-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        transfers.clear()
    }

    fun register(token: Int, transfer: DownloadTransfer) {
        transfers[token] = transfer
    }

    fun unregister(token: Int) {
        transfers.remove(token)
    }

    fun urlFor(token: Int): String = "http://127.0.0.1:$port/s/$token"

    private fun runAcceptLoop(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (error: Throwable) {
                return
            }
            if (!handlerSlots.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            Thread(
                {
                    try {
                        handle(client)
                    } finally {
                        handlerSlots.release()
                    }
                },
                "relay-slsk-http-${threadCounter.incrementAndGet()}",
            ).apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = HTTP_READ_TIMEOUT_MS
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val request = readRequest(input) ?: return
                val token = request.path.removePrefix("/s/").toIntOrNull()
                val transfer = if (request.path.startsWith("/s/")) token?.let(transfers::get) else null
                if (transfer == null) {
                    output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                if (transfer.state() == DownloadTransfer.State.FAILED) {
                    val reason = transfer.failReason() ?: "The transfer failed."
                    output.write("HTTP/1.1 503 Service Unavailable\r\n".toByteArray())
                    output.write("Content-Type: text/plain\r\n".toByteArray())
                    output.write("Connection: close\r\n\r\n".toByteArray())
                    output.write(reason.toByteArray())
                    output.flush()
                    return
                }
                serve(client, output, request, transfer)
            }
        } catch (error: Throwable) {
            runCatching { socket.close() }
        }
    }

    private fun serve(socket: Socket, output: BufferedOutputStream, request: HttpRequest, transfer: DownloadTransfer) {
        val total = transfer.sizeBytes
        val rangeStart = parseRangeStart(request.range, total)
        val waitDeadline = System.currentTimeMillis() + STREAM_WAIT_TIMEOUT_MS
        if (!transfer.awaitBytes(rangeStart, waitDeadline)) {
            val reason = transfer.failReason() ?: "The stream did not reach the requested position."
            output.write("HTTP/1.1 503 Service Unavailable\r\n".toByteArray())
            output.write("Content-Type: text/plain\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(reason.toByteArray())
            output.flush()
            return
        }
        val contentType = audioContentType(transfer.path)
        if (rangeStart > 0 && total > 0) {
            output.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
            output.write("Content-Range: bytes $rangeStart-${total - 1}/$total\r\n".toByteArray())
            output.write("Content-Length: ${total - rangeStart}\r\n".toByteArray())
        } else if (total > 0) {
            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
            output.write("Content-Length: $total\r\n".toByteArray())
        } else {
            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
            output.write("Connection: close\r\n".toByteArray())
        }
        output.write("Accept-Ranges: bytes\r\n".toByteArray())
        output.write("Content-Type: $contentType\r\n\r\n".toByteArray())
        output.flush()
        val chunk = ByteArray(16_384)
        var served = rangeStart
        while (total <= 0 || served < total) {
            val read = transfer.buffer.readInto(chunk, served, chunk.size)
            if (read <= 0) {
                if (transfer.state() == DownloadTransfer.State.FAILED) return
                if (!transfer.awaitBytes(served + 1, System.currentTimeMillis() + STREAM_WAIT_TIMEOUT_MS)) {
                    return
                }
                continue
            }
            try {
                output.write(chunk, 0, read)
                output.flush()
            } catch (error: IOException) {
                return
            }
            served += read
            if (total > 0 && served >= total) break
        }
    }

    private fun parseRangeStart(range: String?, total: Long): Long {
        if (range.isNullOrBlank() || total <= 0) return 0
        val match = Regex("bytes=(\\d+)-").find(range.trim()) ?: return 0
        val start = match.groupValues[1].toLongOrNull() ?: return 0
        return start.coerceIn(0, (total - 1).coerceAtLeast(0))
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val header = StringBuilder()
        while (header.length < MAX_REQUEST_BYTES) {
            val read = try {
                input.read()
            } catch (error: SocketTimeoutException) {
                return null
            }
            if (read < 0) return null
            header.append(read.toChar())
            if (header.endsWith("\r\n\r\n")) break
        }
        val lines = header.toString().split("\r\n").filter { it.isNotBlank() }
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) return null
        var range: String? = null
        for (index in 1 until lines.size) {
            val line = lines[index]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "range") range = value
        }
        return HttpRequest(parts[1], range)
    }

    private fun audioContentType(path: String): String = when (fileExtension(path)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "aif", "aiff" -> "audio/aiff"
        else -> "application/octet-stream"
    }

    private class HttpRequest(val path: String, val range: String?)

    private companion object {
        const val MAX_CONCURRENT_STREAMS = 8
        const val HTTP_READ_TIMEOUT_MS = 10_000
        const val STREAM_WAIT_TIMEOUT_MS = 9_000L
        const val MAX_REQUEST_BYTES = 8_192
    }
}
