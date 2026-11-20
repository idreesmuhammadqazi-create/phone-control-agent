package dev.pocketpilot.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal HTTP/1.1 server: one request per connection, small fixed pool, no TLS. It binds to
 * loopback by default and is meant to be reached over `adb forward` or, opt-in, the local network.
 */
class HttpServer(
    private val port: Int,
    private val bindLan: Boolean,
    private val router: Router,
    private val log: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** The actual port once started; useful when constructed with port 0 (tests). */
    val boundPort: Int
        get() = try {
            server?.localPort ?: 0
        } catch (_: Throwable) {
            0
        }

    fun start(): String? {
        if (running) return null
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            val host = if (bindLan) "0.0.0.0" else "127.0.0.1"
            socket.bind(InetSocketAddress(host, port))
            socket.soTimeout = 1000
            server = socket
            running = true
            pool = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "pilot-http").apply { isDaemon = true }
            }
            Thread({ acceptLoop(socket) }, "pilot-accept").apply {
                isDaemon = true
                start()
            }
            log("listening on $host:$port")
            null
        } catch (t: Throwable) {
            running = false
            val message = t.message ?: t.javaClass.simpleName
            log("listen failed: $message")
            message
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        pool?.shutdownNow()
        pool = null
        log("stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                if (!running) break
                continue
            }
            try {
                pool?.execute { handle(client) } ?: client.close()
            } catch (_: Throwable) {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 15_000
                sock.tcpNoDelay = true
                val request = readRequest(BufferedInputStream(sock.getInputStream())) ?: return
                val result = router.handleHttp(
                    method = request.method,
                    path = request.path,
                    query = request.query,
                    body = request.body,
                    authorization = request.headers["authorization"]
                )
                var contentType = result.contentType
                var body = result.body
                val accept = request.headers["accept"].orEmpty()
                if (accept.contains("text/event-stream") &&
                    !accept.contains("application/json") &&
                    result.status == 200 &&
                    body.isNotBlank()
                ) {
                    body = "event: message\ndata: $body\n\n"
                    contentType = "text/event-stream"
                }
                writeResponse(BufferedOutputStream(sock.getOutputStream()), result.status, contentType, body)
            } catch (t: Throwable) {
                log("request failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRequest(input: InputStream): Request? {
        val headerBytes = ByteArrayOutputStream(512)
        var tail = 0
        while (headerBytes.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
        }
        val head = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").ifEmpty { null }

        val headers = HashMap<String, String>(8)
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1 shl 20) ?: 0
        val body = if (length > 0) {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return Request(method, path, query, headers, body)
    }

    private fun writeResponse(out: BufferedOutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: content-type, authorization, mcp-protocol-version\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Internal Server Error"
    }
}
