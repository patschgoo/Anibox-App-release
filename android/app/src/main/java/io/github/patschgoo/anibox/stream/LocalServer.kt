package io.github.patschgoo.anibox.stream

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A file the player can read from any position. */
interface MediaSource {
    /** Total size in bytes, or -1 if unknown (seeking is then impossible). */
    val length: Long

    /** Opens the file positioned at [offset]. */
    @Throws(IOException::class)
    fun open(offset: Long): InputStream
}

/**
 * The player's own little web server: mpv can't read smb:// on Android, so each episode (and its
 * subtitle files) is served from the share at `http://127.0.0.1:<random port>/<random token>/<n>/<name>`,
 * with byte ranges so mpv can seek. It listens on the loopback address only and answers nothing that
 * doesn't come from this phone itself.
 *
 * Based on CastApp's stream/TokenHttpServer.kt and stream/MediaServer.kt (same owner, GPL-3.0).
 */
class LocalServer(private val log: (String) -> Unit = {}) {
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile var port: Int = -1
        private set
    @Volatile private var running = false

    private var serverSocket: ServerSocket? = null
    private val threads = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "anibox-http-${threads.incrementAndGet()}").apply { isDaemon = true }
    }
    private val openSockets = Collections.synchronizedSet(HashSet<Socket>())
    private val files = ConcurrentHashMap<String, Pair<MediaSource, String>>()
    private val counter = AtomicInteger()

    /** The address it listens on (always the loopback address). */
    val boundAddress: InetAddress? get() = serverSocket?.inetAddress

    @Synchronized
    fun start(): Int {
        if (running) return port
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16)
        serverSocket = ss
        port = ss.localPort
        running = true
        executor.execute { acceptLoop(ss) }
        return port
    }

    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        synchronized(openSockets) {
            openSockets.forEach { try { it.close() } catch (_: IOException) {} }
            openSockets.clear()
        }
        files.clear()
        executor.shutdownNow()
    }

    /** Serves [source] and returns its address; [name] is kept at the end (mpv reads the type and language from it). */
    fun add(source: MediaSource, name: String, mime: String = mimeFor(name)): String {
        val id = counter.incrementAndGet().toString()
        files[id] = source to mime
        return "http://127.0.0.1:$port/$token/$id/${safeName(name)}"
    }

    /** Stops serving everything added so far (the next episode adds its own). */
    fun clear() = files.clear()

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (_: IOException) { break }
            if (!allowed(socket.inetAddress)) {
                try { socket.close() } catch (_: IOException) {}
                continue
            }
            openSockets.add(socket)
            try {
                executor.execute { serve(socket) }
            } catch (_: Exception) {
                closeQuietly(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val req = readRequest(socket.getInputStream()) ?: return
            when {
                req.method != "GET" && req.method != "HEAD" -> head(out, 405, "Method Not Allowed", "text/plain", 0)
                !req.path.startsWith("/$token/") -> head(out, 404, "Not Found", "text/plain", 0)
                else -> {
                    val id = req.path.removePrefix("/$token/").substringBefore('/')
                    val f = files[id]
                    if (f == null) head(out, 404, "Not Found", "text/plain", 0) else serveRange(req, socket, out, f.first, f.second)
                }
            }
            out.flush()
        } catch (_: IOException) {
        } catch (e: RuntimeException) {
            log("Request failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            closeQuietly(socket)
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>) {
        val isHead get() = method == "HEAD"
    }

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
            if (head.length > 16 * 1024) return null
        }
        val lines = head.lines()
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers)
    }

    private fun serveRange(req: Request, socket: Socket, out: OutputStream, source: MediaSource, mime: String) {
        socket.soTimeout = 0
        copyNote.remove()
        val length = source.length
        if (length < 0) {
            head(out, 200, "OK", mime, -1)
            if (!req.isHead) source.open(0).use { copy(it, out, Long.MAX_VALUE) }
            return
        }
        val range = req.headers["range"]?.let { parseRange(it, length) }
        if (range == INVALID_RANGE) {
            head(out, 416, "Range Not Satisfiable", mime, 0, mapOf("Content-Range" to "bytes */$length"))
            return
        }
        val ranges = mapOf("Accept-Ranges" to "bytes")
        val (start, end) = range ?: (0L to length - 1)
        val count = if (length == 0L) 0L else end - start + 1
        val began = System.currentTimeMillis()
        val what = "${req.method} ${req.path.substringAfterLast('/')} bytes $start-$end of $length"
        // Opened before the answer starts, so a share that stopped answering gives mpv a clear error.
        val input = if (req.isHead || count == 0L) null else try {
            source.open(start)
        } catch (e: IOException) {
            log("$what: 503, the share didn't open it after ${System.currentTimeMillis() - began} ms (${e.javaClass.simpleName}: ${e.message})")
            head(out, 503, "Service Unavailable", "text/plain", 0)
            return
        }
        val opened = System.currentTimeMillis() - began
        input.use {
            if (range == null) head(out, 200, "OK", mime, length, ranges)
            else head(out, 206, "Partial Content", mime, count, ranges + ("Content-Range" to "bytes $start-$end/$length"))
            val sent = if (it != null) copy(it, out, count) else 0L
            val ms = System.currentTimeMillis() - began
            val rate = if (ms > 0) sent * 1000 / ms / 1024 else 0
            log("$what: ${if (range == null) 200 else 206}, opened in $opened ms, sent ${sent / 1024} KB in $ms ms ($rate KB/s)${copyNote.get() ?: ""}")
            copyNote.remove()
        }
    }

    /** Why the last [copy] on this thread stopped early (the share's error), for the log line. */
    private val copyNote = ThreadLocal<String?>()

    private fun head(out: OutputStream, code: Int, reason: String, type: String?, length: Long, extra: Map<String, String> = emptyMap()) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Copies up to [count] bytes; returns how many went out. */
    private fun copy(input: InputStream, out: OutputStream, count: Long): Long {
        val buf = ByteArray(256 * 1024)
        var remaining = count
        while (remaining > 0 && running) {
            val n = try {
                input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            } catch (e: IOException) {
                copyNote.set(", the share stopped: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            if (n < 0) {
                copyNote.set(", the file ended early")
                break
            }
            try {
                out.write(buf, 0, n)
            } catch (_: IOException) {
                copyNote.set(", mpv closed the connection") // seeking or stopping: that's normal
                break
            }
            remaining -= n
        }
        try { out.flush() } catch (_: IOException) {}
        return count - remaining
    }

    private fun closeQuietly(socket: Socket) {
        openSockets.remove(socket)
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        val INVALID_RANGE = -1L to -1L

        /** Only this phone itself may ask. */
        fun allowed(peer: InetAddress?): Boolean = peer != null && peer.isLoopbackAddress

        /** A name that is safe in a URL path; keeps the extension and the ".ger"-style language part. */
        fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "media" }

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            "sup" -> "application/octet-stream"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

        /** A single "bytes=" range against [length] bytes: null = the whole file, [INVALID_RANGE] = unsatisfiable. */
        fun parseRange(header: String, length: Long): Pair<Long, Long>? {
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true) || length < 0) return null
            val first = spec.substring(6).split(',').first().trim()
            val dash = first.indexOf('-')
            if (dash < 0) return null
            val startText = first.substring(0, dash).trim()
            val endText = first.substring(dash + 1).trim()
            return if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return null
                if (suffix <= 0 || length == 0L) return INVALID_RANGE
                maxOf(0, length - suffix) to length - 1
            } else {
                val start = startText.toLongOrNull() ?: return null
                if (start >= length) return INVALID_RANGE
                val end = endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
                if (end < start) return INVALID_RANGE
                start to end
            }
        }
    }
}
