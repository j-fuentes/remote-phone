package com.remotephone

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import javax.net.ssl.SSLServerSocket

/**
 * Serves the static web client (web/index.html + web/remotephone.js, packaged as assets) over
 * HTTPS with the phone's own certificate, so a browser on the network can open
 * https://<phone-ip>:8766/ and get a client already pointed at this phone.
 *
 * Served over HTTPS (not plain HTTP) because browsers only expose WebCodecs, the low-latency
 * decoder, to secure contexts. The certificate is the one the WebSocket uses: Chrome remembers
 * the exception per host, so accepting it once here also covers wss://<phone-ip>:8765.
 */
class WebClientServer(private val context: Context, private val port: Int = PORT) {

    companion object {
        private const val TAG = "WebClientServer"
        const val PORT = 8766

        /** Link that opens the client connected to this phone, its certificate fingerprint included. */
        fun url(ip: String): String = Uri.Builder()
            .scheme("https")
            .encodedAuthority("$ip:$PORT")
            .path("/")
            .appendQueryParameter("host", ip)
            .appendQueryParameter("port", ScreenCaptureService.WS_PORT.toString())
            .appendQueryParameter("fp", Tls.fingerprintHex())
            .appendQueryParameter("connect", "1")
            .build().toString()

        private val FILES = mapOf(
            "/" to ("index.html" to "text/html; charset=utf-8"),
            "/index.html" to ("index.html" to "text/html; charset=utf-8"),
            "/remotephone.js" to ("remotephone.js" to "text/javascript; charset=utf-8"),
        )
    }

    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "WebClientServer") }

    fun start() {
        val server = Tls.serverContext().serverSocketFactory.createServerSocket() as SSLServerSocket
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        socket = server
        Thread({ acceptLoop(server) }, "WebClientAccept").start()
        Log.i(TAG, "Web client served on port $port")
    }

    fun stop() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                pool.execute { serve(client) }
            } catch (e: SocketException) {
                break  // closed
            } catch (e: Exception) {
                Log.w(TAG, "accept failed", e)
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                while (true) {  // headers are not needed
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1)?.substringBefore('?')?.substringBefore('#') ?: "/"
                val out = s.getOutputStream()
                val file = FILES[path]
                when {
                    method != "GET" && method != "HEAD" -> respond(out, 405, "text/plain", "Method not allowed".toByteArray(), method == "HEAD")
                    file == null -> respond(out, 404, "text/plain", "Not found".toByteArray(), method == "HEAD")
                    else -> {
                        val body = context.assets.open("web/" + file.first).use { it.readBytes() }
                        respond(out, 200, file.second, body, method == "HEAD")
                    }
                }
            } catch (e: Exception) {
                // TLS handshake refused by a browser that has not accepted the certificate yet, timeouts...
                Log.d(TAG, "request failed: ${e.message}")
            }
        }
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, head: Boolean) {
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (!head) out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("line too long")
            sb.append(c.toChar())
        }
    }
}
