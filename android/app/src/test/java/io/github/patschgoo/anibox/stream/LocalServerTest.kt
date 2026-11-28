package io.github.patschgoo.anibox.stream

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

class LocalServerTest {
    private val data = ByteArray(300_000) { (it * 7 % 251).toByte() }
    private val source = object : MediaSource {
        override val length = data.size.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }
    private lateinit var server: LocalServer
    private lateinit var url: String

    @Before fun start() {
        server = LocalServer()
        server.start()
        url = server.add(source, "Kaze no Tabi 01.mkv")
    }

    @After fun stop() = server.stop()

    private class Answer(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    /** A plain HTTP/1.1 request over a real socket. */
    private fun get(path: String, range: String? = null, method: String = "GET"): Answer {
        Socket(InetAddress.getByName("127.0.0.1"), server.port).use { s -> // the address mpv is given
            val req = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                if (range != null) append("Range: $range\r\n")
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray())
            val all = s.getInputStream().readBytes()
            val split = (0 until all.size - 3).first { all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte() }
            val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val code = head[0].split(' ')[1].toInt()
            val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return Answer(code, headers, all.copyOfRange(split + 4, all.size))
        }
    }

    private val path get() = url.removePrefix("http://127.0.0.1:${server.port}")

    @Test fun addressShape() {
        assertTrue(url.matches(Regex("^http://127\\.0\\.0\\.1:\\d+/[0-9a-f]{32}/\\d+/Kaze_no_Tabi_01\\.mkv$")))
        assertTrue(server.boundAddress!!.isLoopbackAddress)
        assertEquals("127.0.0.1", server.boundAddress!!.hostAddress) // never ::1: mpv is given 127.0.0.1
    }

    @Test fun fullGet() {
        val a = get(path)
        assertEquals(200, a.code)
        assertEquals("300000", a.headers["content-length"])
        assertEquals("bytes", a.headers["accept-ranges"])
        assertEquals("video/x-matroska", a.headers["content-type"])
        assertArrayEquals(data, a.body)
    }

    @Test fun head() {
        val a = get(path, method = "HEAD")
        assertEquals(200, a.code)
        assertEquals("300000", a.headers["content-length"])
        assertEquals(0, a.body.size)
    }

    @Test fun range() {
        val a = get(path, "bytes=1000-1999")
        assertEquals(206, a.code)
        assertEquals("bytes 1000-1999/300000", a.headers["content-range"])
        assertArrayEquals(data.copyOfRange(1000, 2000), a.body)
    }

    @Test fun openEndedAndSuffixRanges() {
        val a = get(path, "bytes=299000-")
        assertEquals(206, a.code)
        assertArrayEquals(data.copyOfRange(299000, 300000), a.body)
        val b = get(path, "bytes=-500")
        assertEquals("bytes 299500-299999/300000", b.headers["content-range"])
        assertArrayEquals(data.copyOfRange(299500, 300000), b.body)
        val c = get(path, "bytes=299990-400000") // past the end: cut to the end
        assertArrayEquals(data.copyOfRange(299990, 300000), c.body)
    }

    @Test fun unsatisfiableRange() {
        val a = get(path, "bytes=300000-")
        assertEquals(416, a.code)
        assertEquals("bytes */300000", a.headers["content-range"])
    }

    @Test fun wrongTokenOrUnknownFile() {
        assertEquals(404, get(path.replace(server.token, "0".repeat(32))).code)
        assertEquals(404, get("/${server.token}/999/x.mkv").code)
        assertEquals(404, get("/").code)
        assertEquals(405, get(path, method = "POST").code)
    }

    @Test fun clearedFilesAreGone() {
        server.clear()
        assertEquals(404, get(path).code)
    }

    @Test fun aFailingShareGives503() {
        val broken = object : MediaSource {
            override val length = 100L
            override fun open(offset: Long): InputStream = throw IOException("The share stopped answering")
        }
        val p = server.add(broken, "x.mkv").removePrefix("http://127.0.0.1:${server.port}")
        assertEquals(503, get(p).code)
    }

    @Test fun onlyThisPhone() {
        assertTrue(LocalServer.allowed(InetAddress.getLoopbackAddress()))
        assertTrue(LocalServer.allowed(InetAddress.getByName("127.0.0.2")))
        assertFalse(LocalServer.allowed(InetAddress.getByName("192.0.2.10")))
        assertFalse(LocalServer.allowed(null))
    }

    @Test fun names() {
        assertEquals("Kaze_no_Tabi_01.ger.srt", LocalServer.safeName("Kaze no Tabi 01.ger.srt"))
        assertEquals("application/x-subrip", LocalServer.mimeFor("a.SRT"))
        assertEquals("text/x-ssa", LocalServer.mimeFor("a.ass"))
    }
}
