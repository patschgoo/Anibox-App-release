package io.github.patschgoo.anibox.net

import io.github.patschgoo.anibox.debug.DebugLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** The addresses saved per host on this phone (Settings → Host addresses). */
interface AddressBook {
    fun address(host: String): String?
    fun remember(host: String, address: String, how: Found)
}

/**
 * Opens and keeps the SMB connections: finds each host's address ([HostResolver]), takes the sign-in
 * for the share ([Credentials]: the share's own, else the host's) and keeps one connection per share.
 */
class ShareAccess(
    private val credentials: Credentials,
    private val addresses: AddressBook,
    private val resolver: HostResolver,
    private val link: () -> Link = { Link.UNKNOWN },
) {
    private val lock = Any()
    private val open = HashMap<String, SmbConnection>()
    private val resolved = HashMap<String, Candidate>()
    private val notFound = HashMap<String, Long>()

    private fun key(host: String, share: String) = host.trim().lowercase() + "\\" + share.trim().lowercase()

    /**
     * The host's address (the first candidate that answers); throws [SmbProblem] HOST_NOT_FOUND (a name
     * nothing answered for) or NO_ANSWER (an address that doesn't answer), worded by how the phone is
     * connected ([Reach]).
     */
    @Throws(SmbProblem::class)
    fun address(host: String, hint: String?): Candidate {
        val h = host.lowercase()
        synchronized(lock) { resolved[h] }?.let { return it }
        fun missing() = Reach.notReached(host, safeLink())
        // Looked for in vain a moment ago (each look takes seconds): don't look again right away.
        if (synchronized(lock) { notFound[h] }?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true) throw missing()
        val began = System.currentTimeMillis()
        val c = resolver.resolve(host, hint)
        if (c == null) {
            DebugLog.w("shares", "No address for $host answered (hint ${hint ?: "none"}, ${System.currentTimeMillis() - began} ms, ${safeLink()})")
            synchronized(lock) { notFound[h] = System.currentTimeMillis() }
            throw missing()
        }
        DebugLog.i("shares", "$host is at ${c.address} (${c.how}, ${System.currentTimeMillis() - began} ms)")
        synchronized(lock) { notFound.remove(h) }
        if (c.how != Found.LITERAL && c.how != Found.SAVED) addresses.remember(host, c.address, c.how)
        synchronized(lock) { resolved[host.lowercase()] = c }
        return c
    }

    private fun safeLink(): Link = try {
        link()
    } catch (_: Exception) {
        Link.UNKNOWN
    }

    /** A connection to [share] on [host] (made on first use, kept for later). */
    @Throws(IOException::class)
    fun connection(host: String, share: String, hint: String?): SmbConnection {
        val k = key(host, share)
        synchronized(lock) { open[k] }?.let { return it }
        val address = address(host, hint).address
        val conn = SmbConnection(SmbTarget(host, address, share, credentials.forShare(host, share)))
        DebugLog.i("shares", "New connection to \\\\$host\\$share ($address)")
        synchronized(lock) {
            open[k]?.let { conn.close(); return it }
            open[k] = conn
        }
        return conn
    }

    /** Forgets connections and found addresses (after a new sign-in or a new address). */
    fun reset(host: String? = null) {
        val gone = synchronized(lock) {
            val keys = open.keys.filter { host == null || it.startsWith(host.trim().lowercase() + "\\") }
            if (host == null) {
                resolved.clear()
                notFound.clear()
            } else {
                resolved.remove(host.lowercase())
                notFound.remove(host.lowercase())
            }
            keys.mapNotNull { open.remove(it) }
        }
        if (gone.isNotEmpty()) DebugLog.i("shares", "Closed ${gone.size} connection(s)${if (host != null) " to $host" else ""}")
        gone.forEach { it.close() }
    }

    companion object {
        const val SMB_PORT = 445
        const val RETRY_AFTER_MS = 30_000L

        /** A quick look whether [address] takes connections on the SMB port. */
        fun answers(address: String, timeoutMs: Int = 2500): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(address, SMB_PORT), timeoutMs) }
            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
