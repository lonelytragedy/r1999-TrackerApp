package com.lonelytragedy.r1999trackerapp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MitmProxy(private val port: Int, private val onUrl: (String) -> Unit) {

    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private lateinit var serverFactory: SSLSocketFactory

    fun start() {
        val ctx = CertFactory.serverContext()
        serverFactory = ctx.socketFactory
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress("0.0.0.0", port))
        server = s
        running = true
        Bus.logLine("Proxy listening on 0.0.0.0:$port")
        pool.execute { acceptLoop() }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
        Bus.logLine("Proxy stopped")
    }

    private fun acceptLoop() {
        val srv = server ?: return
        while (running) {
            val client = try {
                srv.accept()
            } catch (_: Exception) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            if (parts[0].equals("CONNECT", true)) {
                Bus.logLine("CONNECT ${parts[1]}")
                handleConnect(client, input, parts[1])
            } else {
                Bus.logLine("${parts[0]} ${parts[1]}")
                handlePlain(client, input, parts)
            }
        } catch (e: Exception) {
            Bus.logLine("err: ${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleConnect(client: Socket, input: InputStream, hostPort: String) {
        while (true) {
            val l = readLine(input) ?: return
            if (l.isEmpty()) break
        }

        val host = hostPort.substringBefore(":")
        val targetPort = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443

        val out = client.getOutputStream()
        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        out.flush()

        val clientTls = serverFactory.createSocket(client, host, targetPort, true) as SSLSocket
        clientTls.useClientMode = false
        try {
            clientTls.startHandshake()
        } catch (e: Exception) {
            Bus.logLine("TLS handshake with app failed for $host: ${e.message ?: ""}")
            return
        }

        val real = try {
            val r = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(host, targetPort) as SSLSocket
            r.startHandshake()
            r
        } catch (e: Exception) {
            Bus.logLine("upstream TLS to $host failed: ${e.message ?: ""}")
            return
        }

        val up = Thread { scanPipe(clientTls.inputStream, real.getOutputStream(), host) }
        val down = Thread { pipe(real.inputStream, clientTls.getOutputStream()) }
        up.start()
        down.start()
        up.join()
        down.join()

        close(clientTls)
        close(real)
    }

    private fun handlePlain(client: Socket, input: InputStream, parts: List<String>) {
        val target = parts[1]
        if (!target.startsWith("http://")) return
        val body = target.removePrefix("http://")
        val hostPort = body.substringBefore("/")
        val host = hostPort.substringBefore(":")
        val targetPort = hostPort.substringAfter(":", "80").toIntOrNull() ?: 80
        val path = "/" + body.substringAfter("/", "")
        if (path.contains("query/summon")) capture("http://$host$path")

        val headers = ArrayList<String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            headers.add(l)
        }

        val server = Socket(host, targetPort)
        val sout = server.getOutputStream()
        val version = parts.getOrElse(2) { "HTTP/1.1" }
        sout.write("${parts[0]} $path $version\r\n".toByteArray())
        for (h in headers) sout.write("$h\r\n".toByteArray())
        sout.write("\r\n".toByteArray())
        sout.flush()

        val up = Thread { pipe(input, sout) }
        val down = Thread { pipe(server.getInputStream(), client.getOutputStream()) }
        up.start()
        down.start()
        up.join()
        down.join()
        close(server)
    }

    private fun scanPipe(from: InputStream, to: OutputStream, host: String) {
        val buf = ByteArray(16384)
        val window = StringBuilder()
        try {
            while (true) {
                val n = from.read(buf)
                if (n == -1) break
                to.write(buf, 0, n)
                to.flush()
                window.append(String(buf, 0, n, Charsets.ISO_8859_1))
                extract(window, host)
                if (window.length > 65536) window.delete(0, window.length - 4096)
            }
        } catch (_: Exception) {
        }
    }

    private fun extract(window: StringBuilder, host: String) {
        val idx = window.indexOf("query/summon")
        if (idx < 0) return
        val lineEnd = window.indexOf("\n", idx)
        if (lineEnd < 0) return
        var lineStart = window.lastIndexOf("\n", idx)
        lineStart = if (lineStart < 0) 0 else lineStart + 1
        val line = window.substring(lineStart, lineEnd).trim()
        val parts = line.split(" ")
        if (parts.size >= 2 && parts[1].startsWith("/")) {
            capture("https://$host${parts[1]}")
        }
        window.setLength(0)
    }

    private fun capture(url: String) {
        Bus.logLine("FOUND summon link")
        onUrl(url)
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = from.read(buf)
                if (n == -1) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val s = buf.toString("ISO-8859-1")
                return if (s.endsWith("\r")) s.dropLast(1) else s
            }
            buf.write(b)
        }
    }

    private fun close(s: Socket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }
}
