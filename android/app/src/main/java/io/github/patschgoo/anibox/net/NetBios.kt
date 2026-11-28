package io.github.patschgoo.anibox.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * NetBIOS name queries (RFC 1001/1002, "NB" over UDP port 137): how Windows finds "MEDIABOX" when no
 * DNS server knows the name. The question is sent as a broadcast to the Wi-Fi network; the box with
 * that name answers this phone directly (a unicast reply, so no multicast lock is needed).
 */
object NetBios {
    const val PORT = 137

    /** Service suffixes: 0x20 = file server, 0x00 = workstation. */
    const val FILE_SERVER = 0x20
    const val WORKSTATION = 0x00

    /**
     * The RFC 1001 "first-level" encoding: the name in upper case, padded with spaces to 15 bytes plus
     * the suffix byte, each byte as two letters 'A' + high nibble, 'A' + low nibble (32 letters).
     */
    fun encodeName(name: String, suffix: Int = FILE_SERVER): ByteArray {
        val raw = ByteArray(16) { ' '.code.toByte() }
        val bytes = name.uppercase().toByteArray(Charsets.US_ASCII)
        bytes.copyInto(raw, 0, 0, minOf(15, bytes.size))
        raw[15] = suffix.toByte()
        val out = ByteArray(32)
        for (i in 0 until 16) {
            val b = raw[i].toInt() and 0xFF
            out[2 * i] = ('A'.code + (b shr 4)).toByte()
            out[2 * i + 1] = ('A'.code + (b and 0x0F)).toByte()
        }
        return out
    }

    /** The name in [encoded] (32 letters) without padding and suffix, and the suffix. */
    fun decodeName(encoded: ByteArray): Pair<String, Int>? {
        if (encoded.size != 32) return null
        val raw = ByteArray(16)
        for (i in 0 until 16) {
            val hi = encoded[2 * i] - 'A'.code.toByte()
            val lo = encoded[2 * i + 1] - 'A'.code.toByte()
            if (hi !in 0..15 || lo !in 0..15) return null
            raw[i] = ((hi shl 4) or lo).toByte()
        }
        return String(raw, 0, 15, Charsets.US_ASCII).trimEnd() to (raw[15].toInt() and 0xFF)
    }

    /** A name query (50 bytes): header with the broadcast and recursion-desired flags, one NB/IN question. */
    fun query(name: String, transactionId: Int, suffix: Int = FILE_SERVER, broadcast: Boolean = true): ByteArray {
        val out = ByteArray(50)
        out[0] = (transactionId shr 8).toByte()
        out[1] = transactionId.toByte()
        out[2] = 0x01 // opcode 0 (query), RD
        out[3] = if (broadcast) 0x10 else 0x00 // B (broadcast)
        out[5] = 1 // QDCOUNT = 1
        out[12] = 0x20 // label length 32
        encodeName(name, suffix).copyInto(out, 13)
        out[45] = 0 // end of name (no scope)
        out[47] = 0x20 // QTYPE NB
        out[49] = 0x01 // QCLASS IN
        return out
    }

    /**
     * The IPv4 addresses in a positive name query response to [transactionId] for [name] (any suffix
     * when [name] is null), or an empty list for anything else (other answers, errors, broken packets).
     */
    fun parseResponse(packet: ByteArray, length: Int, transactionId: Int, name: String? = null): List<String> {
        if (length < 12 + 2 + 10) return emptyList()
        fun u8(i: Int) = packet[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != (transactionId and 0xFFFF)) return emptyList()
        val flags = u16(2)
        if (flags and 0x8000 == 0) return emptyList() // not a response
        if ((flags shr 11) and 0x0F != 0) return emptyList() // not a query response
        if (flags and 0x000F != 0) return emptyList() // RCODE: an error (e.g. name not found)
        if (u16(6) < 1) return emptyList() // no answers
        var p = 12
        // The answer's name: a 32-letter label (+ optional scope labels) or a pointer to one.
        var answered: ByteArray? = null
        while (true) {
            if (p >= length) return emptyList()
            val len = u8(p)
            if (len == 0) {
                p++
                break
            }
            if (len and 0xC0 == 0xC0) {
                p += 2
                break
            }
            if (p + 1 + len > length) return emptyList()
            if (answered == null && len == 32) answered = packet.copyOfRange(p + 1, p + 33)
            p += 1 + len
        }
        if (name != null && answered != null) {
            val got = decodeName(answered)?.first ?: return emptyList()
            if (!got.equals(name.take(15), ignoreCase = true)) return emptyList()
        }
        if (p + 10 > length) return emptyList()
        val type = u16(p)
        val cls = u16(p + 2)
        val rdLength = u16(p + 8)
        p += 10
        if (type != 0x20 || cls != 0x01 || p + rdLength > length) return emptyList()
        val out = ArrayList<String>()
        var i = p
        while (i + 6 <= p + rdLength) {
            val ip = "${u8(i + 2)}.${u8(i + 3)}.${u8(i + 4)}.${u8(i + 5)}"
            if (ip != "0.0.0.0" && ip !in out) out += ip
            i += 6
        }
        return out
    }

    /** A positive response (for the tests and for documentation): [addresses] as NB records, TTL 300 s. */
    fun response(name: String, transactionId: Int, addresses: List<String>, suffix: Int = FILE_SERVER): ByteArray {
        val rd = ByteArray(addresses.size * 6)
        addresses.forEachIndexed { n, a ->
            val parts = a.split('.').map { it.toInt() }
            for (k in 0..3) rd[n * 6 + 2 + k] = parts[k].toByte()
        }
        val head = byteArrayOf(
            (transactionId shr 8).toByte(), transactionId.toByte(), 0x85.toByte(), 0x00, 0, 0, 0, 1, 0, 0, 0, 0, 0x20,
        )
        val tail = byteArrayOf(
            0, 0, 0x20, 0, 0x01, 0, 0, 0x01, 0x2C, (rd.size shr 8).toByte(), rd.size.toByte(),
        )
        return head + encodeName(name, suffix) + tail + rd
    }

    /**
     * Asks [broadcasts] (the Wi-Fi network's broadcast address, then 255.255.255.255) who [name] is, file
     * server first, then workstation; waits up to [timeoutMs] each. Returns the first address found.
     */
    fun lookup(name: String, broadcasts: List<InetAddress>, timeoutMs: Int = 1200, port: Int = PORT): String? {
        if (name.isEmpty() || name.length > 15 || name.contains('.')) return null
        val targets = broadcasts.ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }
        try {
            DatagramSocket(null as java.net.SocketAddress?).use { socket ->
                socket.broadcast = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = timeoutMs
                val buf = ByteArray(1024)
                for (suffix in listOf(FILE_SERVER, WORKSTATION)) {
                    val id = Random.nextInt(1, 0xFFFF)
                    val q = query(name, id, suffix)
                    for (t in targets) {
                        try {
                            socket.send(DatagramPacket(q, q.size, t, port))
                        } catch (_: IOException) {
                        }
                    }
                    val until = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < until) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            socket.soTimeout = maxOf(1, (until - System.currentTimeMillis()).toInt())
                            socket.receive(pkt)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        parseResponse(pkt.data, pkt.length, id, name).firstOrNull()?.let { return it }
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        return null
    }
}
