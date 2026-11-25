package io.github.patschgoo.anibox.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class NetBiosTest {
    @Test fun firstLevelEncoding() {
        // RFC 1001 section 14.1: "FRED" (padded with spaces, suffix 0x20) encodes to this
        assertEquals("EGFCEFEECACACACACACACACACACACAAA", String(NetBios.encodeName("FRED", 0x00)))
        assertEquals("EGFCEFEECACACACACACACACACACACACA", String(NetBios.encodeName("fred", 0x20)))
        assertEquals("MEDIABOX" to 0x20, NetBios.decodeName(NetBios.encodeName("MEDIABOX")))
        assertEquals(32, NetBios.encodeName("A-VERY-LONG-NAME-INDEED").size) // cut to 15 letters
    }

    @Test fun queryPacket() {
        val q = NetBios.query("MEDIABOX", 0x1234)
        assertEquals(50, q.size)
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x01, 0x10, 0, 1, 0, 0, 0, 0, 0, 0, 0x20), q.copyOf(13))
        assertEquals("MEDIABOX" to 0x20, NetBios.decodeName(q.copyOfRange(13, 45)))
        assertArrayEquals(byteArrayOf(0, 0, 0x20, 0, 1), q.copyOfRange(45, 50))
    }

    @Test fun parsesAPositiveAnswer() {
        val r = NetBios.response("MEDIABOX", 0x4321, listOf("192.0.2.10", "198.51.100.7"))
        assertEquals(listOf("192.0.2.10", "198.51.100.7"), NetBios.parseResponse(r, r.size, 0x4321, "MEDIABOX"))
        assertEquals(listOf("192.0.2.10", "198.51.100.7"), NetBios.parseResponse(r, r.size, 0x4321))
    }

    @Test fun ignoresOtherAnswers() {
        val r = NetBios.response("MEDIABOX", 0x4321, listOf("192.0.2.10"))
        assertTrue(NetBios.parseResponse(r, r.size, 0x9999).isEmpty()) // another question's answer
        assertTrue(NetBios.parseResponse(r, r.size, 0x4321, "OTHERBOX").isEmpty()) // another name
        assertTrue(NetBios.parseResponse(r, 20, 0x4321).isEmpty()) // cut off
        val q = NetBios.query("MEDIABOX", 0x4321)
        assertTrue(NetBios.parseResponse(q, q.size, 0x4321).isEmpty()) // a question, not an answer
        val negative = r.copyOf().also { it[3] = 0x03 } // RCODE 3: name not found
        assertTrue(NetBios.parseResponse(negative, negative.size, 0x4321).isEmpty())
    }

    @Test fun answerWithANamePointer() {
        val full = NetBios.response("MEDIABOX", 0x0102, listOf("192.0.2.10"))
        // header + 0xC00C pointer instead of the 34-byte name
        val ptr = full.copyOf(12) + byteArrayOf(0xC0.toByte(), 0x0C) + full.copyOfRange(46, full.size)
        assertEquals(listOf("192.0.2.10"), NetBios.parseResponse(ptr, ptr.size, 0x0102))
    }

    @Test fun lookupOverUdp() {
        // A fake "Shield" on the loopback answers the question like a NetBIOS name server would.
        val server = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val t = thread(isDaemon = true) {
            val buf = ByteArray(576)
            repeat(4) {
                val p = DatagramPacket(buf, buf.size)
                try { server.receive(p) } catch (_: Exception) { return@thread }
                val id = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                val asked = NetBios.decodeName(buf.copyOfRange(13, 45))!!
                if (asked.first == "MEDIABOX" && asked.second == NetBios.FILE_SERVER) {
                    val r = NetBios.response("MEDIABOX", id, listOf("192.0.2.10"))
                    server.send(DatagramPacket(r, r.size, p.socketAddress))
                }
            }
        }
        try {
            assertEquals("192.0.2.10", NetBios.lookup("MEDIABOX", listOf(InetAddress.getLoopbackAddress()), 1500, server.localPort))
            assertNull(NetBios.lookup("NOBODY", listOf(InetAddress.getLoopbackAddress()), 200, server.localPort))
        } finally {
            server.close()
            t.join(2000)
        }
    }

    @Test fun namesThatArentNetbiosNames() {
        assertNull(NetBios.lookup("mediabox.example.org", emptyList(), 50))
        assertNull(NetBios.lookup("", emptyList(), 50))
    }
}
