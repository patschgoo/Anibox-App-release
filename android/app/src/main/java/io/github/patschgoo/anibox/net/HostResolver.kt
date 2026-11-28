package io.github.patschgoo.anibox.net

import io.github.patschgoo.anibox.sync.QrCode

/** Where an address for a host came from. */
enum class Found(val label: String) {
    LITERAL("typed as an address"),
    SAVED("saved on this phone"),
    PC("from the PC"),
    DNS("from the network's name server"),
    NETBIOS("found by its Windows name"),
}

data class Candidate(val address: String, val how: Found)

/**
 * Finds the address of a share's host. The PC knows the Shield by its Windows name ("MEDIABOX"), which
 * Android's name lookup often can't resolve. The order:
 *
 * 1. the host is an address already;
 * 2. the address saved on this phone for that host (typed in Settings, or the one that worked last time);
 * 3. the address AniBox on the PC found (QR code / library.json `address`);
 * 4. DNS (also "<host>.local");
 * 5. a NetBIOS name query on the Wi-Fi network;
 * 6. none: the app asks you to type it (and saves it).
 *
 * Each candidate is tried with a quick connection to the SMB port; the first that answers wins.
 */
class HostResolver(
    private val saved: (String) -> String?,
    private val dns: (String) -> List<String>,
    private val netbios: (String) -> String?,
    private val reachable: (String) -> Boolean,
) {
    /** The candidates in order, each computed only when the ones before it didn't answer. */
    fun candidates(host: String, hint: String?): Sequence<Candidate> = sequence {
        val h = host.trim()
        if (QrCode.isIpv4(h)) {
            yield(Candidate(h, Found.LITERAL))
            return@sequence
        }
        safe { saved(h) }?.takeIf { QrCode.isIpv4(it) }?.let { yield(Candidate(it, Found.SAVED)) }
        hint?.trim()?.takeIf { QrCode.isIpv4(it) }?.let { yield(Candidate(it, Found.PC)) }
        val names = if (h.contains('.')) listOf(h) else listOf(h, "$h.local")
        for (n in names) {
            for (a in safe { dns(n) } ?: emptyList()) if (QrCode.isIpv4(a)) yield(Candidate(a, Found.DNS))
        }
        safe { netbios(h) }?.takeIf { QrCode.isIpv4(it) }?.let { yield(Candidate(it, Found.NETBIOS)) }
    }

    /** The first candidate that answers, or null (then ask for the address). Each address is tried once. */
    fun resolve(host: String, hint: String?): Candidate? {
        val tried = HashSet<String>()
        for (c in candidates(host, hint)) {
            if (!tried.add(c.address)) continue
            if (reachable(c.address)) return c
        }
        return null
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}
