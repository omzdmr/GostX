package cn.liukebin.gostx.lantern

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small LAN-facing HTTP/HTTPS proxy that sends every upstream connection through
 * Radiance's local SOCKS5 listener. It intentionally supports the two forms TVs
 * normally use: HTTP CONNECT for TLS and absolute-form HTTP requests.
 */
class LanHttpToSocksProxy(
    private val listenHost: String = "0.0.0.0",
    private val listenPort: Int = 8080,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 18080
) {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(listenHost, listenPort))
        serverSocket = server
        pool.execute {
            while (running.get()) {
                try {
                    val client = server.accept()
                    client.soTimeout = 30_000
                    pool.execute { handleClient(client) }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        client.use { c ->
            val input = BufferedInputStream(c.getInputStream())
            val output = BufferedOutputStream(c.getOutputStream())
            val headerBytes = readHeader(input) ?: return
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.trim().orEmpty()
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 3) return

            if (parts[0].equals("CONNECT", ignoreCase = true)) {
                val hp = splitHostPort(parts[1], 443) ?: return
                connectViaSocks(hp.first, hp.second).use { upstream ->
                    output.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: GostX-Radiance\r\n\r\n".toByteArray())
                    output.flush()
                    tunnel(c, upstream)
                }
                return
            }

            val hostHeader = lines.drop(1)
                .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            val uri = runCatching { URI(parts[1]) }.getOrNull()
            val host = uri?.host ?: hostHeader?.substringBeforeLast(':') ?: return
            val port = when {
                uri?.port != null && uri.port > 0 -> uri.port
                hostHeader?.contains(':') == true -> hostHeader.substringAfterLast(':').toIntOrNull() ?: 80
                else -> 80
            }
            val path = if (uri?.isAbsolute == true) {
                buildString {
                    append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                    if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
                }
            } else parts[1]

            connectViaSocks(host, port).use { upstream ->
                val upOut = BufferedOutputStream(upstream.getOutputStream())
                val rebuilt = StringBuilder()
                rebuilt.append(parts[0]).append(' ').append(path).append(' ').append(parts[2]).append("\r\n")
                var hasConnection = false
                for (line in lines.drop(1)) {
                    if (line.isEmpty()) continue
                    if (line.startsWith("Proxy-Connection:", ignoreCase = true)) continue
                    if (line.startsWith("Connection:", ignoreCase = true)) {
                        rebuilt.append("Connection: close\r\n")
                        hasConnection = true
                    } else {
                        rebuilt.append(line).append("\r\n")
                    }
                }
                if (!hasConnection) rebuilt.append("Connection: close\r\n")
                rebuilt.append("\r\n")
                upOut.write(rebuilt.toString().toByteArray(Charsets.ISO_8859_1))
                upOut.flush()

                copyUntilClose(BufferedInputStream(upstream.getInputStream()), output)
            }
        }
    }

    private fun connectViaSocks(host: String, port: Int): Socket {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        val socket = Socket(proxy)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress.createUnresolved(host, port), 20_000)
        return socket
    }

    private fun tunnel(client: Socket, upstream: Socket) {
        val latch = java.util.concurrent.CountDownLatch(2)
        pool.execute {
            try { copyUntilClose(client.getInputStream(), upstream.getOutputStream()) } finally {
                runCatching { upstream.shutdownOutput() }; latch.countDown()
            }
        }
        pool.execute {
            try { copyUntilClose(upstream.getInputStream(), client.getOutputStream()) } finally {
                runCatching { client.shutdownOutput() }; latch.countDown()
            }
        }
        runCatching { latch.await() }
    }

    private fun copyUntilClose(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            output.write(buffer, 0, n)
            output.flush()
        }
    }

    private fun readHeader(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream(2048)
        var state = 0
        while (out.size() < 64 * 1024) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return out.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int>? {
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 0) return null
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val idx = value.lastIndexOf(':')
        return if (idx > 0 && value.indexOf(':') == idx) {
            value.substring(0, idx) to (value.substring(idx + 1).toIntOrNull() ?: defaultPort)
        } else value to defaultPort
    }
}
