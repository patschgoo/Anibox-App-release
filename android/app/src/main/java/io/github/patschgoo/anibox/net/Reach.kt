package io.github.patschgoo.anibox.net

import io.github.patschgoo.anibox.sync.QrCode

/** How this phone is connected right now (read from Android when a share doesn't answer). */
data class Link(val kind: Kind, val vpn: Boolean = false) {
    enum class Kind { NONE, WIFI, MOBILE, OTHER, UNKNOWN }

    companion object {
        val UNKNOWN = Link(Kind.UNKNOWN)
    }
}

/**
 * Plain English for "the Shield can't be reached", by how the phone is connected. The Shield's
 * address is usually one inside the home network (192.168.x.x and the like), which mobile data
 * or someone else's Wi-Fi can't reach: then the answer is "home Wi-Fi, or Tailscale away from home",
 * not "type its address".
 */
object Reach {
    /** 10/8, 172.16/12, 192.168/16 and 169.254/16: addresses that exist only inside a home network. */
    fun isHome(address: String): Boolean {
        val b = bytes(address) ?: return false
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
    }

    /** 100.64/10: the addresses Tailscale gives its devices. */
    fun isTailscale(address: String): Boolean {
        val b = bytes(address) ?: return false
        return b[0] == 100 && b[1] in 64..127
    }

    /** The problem to show when nothing answered for [host] (a name or an address). */
    fun notReached(host: String, link: Link): SmbProblem {
        val h = host.trim()
        val literal = QrCode.isIpv4(h)
        val problem = if (literal) Problem.NO_ANSWER else Problem.HOST_NOT_FOUND
        return SmbProblem(problem, message(h, literal, link))
    }

    private fun message(host: String, literal: Boolean, link: Link): String {
        if (link.kind == Link.Kind.NONE) return "This phone isn't connected to any network right now. Connect it to your Wi-Fi and try again."
        val mobile = link.kind == Link.Kind.MOBILE && !link.vpn
        if (!literal) {
            return if (mobile) {
                "Can't find $host: this phone is on mobile data, and the Shield is in your home network. " +
                    "At home, connect to your Wi-Fi. Away from home, turn on Tailscale first (once it's set up)."
            } else {
                "Can't find $host on this network. Type its address (your router's list of devices shows it)."
            }
        }
        if (isTailscale(host) && !link.vpn) return "$host is a Tailscale address: turn on Tailscale on this phone first."
        if (!isHome(host)) return "$host doesn't answer. Is the Shield on, and is this phone on the same network?"
        return when {
            link.vpn ->
                "$host doesn't answer through the VPN. Is Tailscale on, on this phone and on the Shield, and is the Shield's " +
                    "route to your home network approved? Is the Shield on?"
            mobile ->
                "This phone is on mobile data, and $host is an address inside your home network, so it can't be reached from here. " +
                    "At home, connect to your Wi-Fi. Away from home, turn on Tailscale first (once it's set up)."
            link.kind == Link.Kind.WIFI ->
                "$host doesn't answer on this Wi-Fi. Is this your home Wi-Fi (not a guest network), and is the Shield on? " +
                    "Away from home, turn on Tailscale first."
            else ->
                "$host doesn't answer. It's an address inside your home network: connect to your home Wi-Fi, " +
                    "or turn on Tailscale when you're away from home."
        }
    }

    private fun bytes(address: String): IntArray? {
        val a = address.trim()
        if (!QrCode.isIpv4(a)) return null
        val parts = a.split('.').map { it.toIntOrNull() ?: return null }
        return if (parts.size == 4 && parts.all { it in 0..255 }) parts.toIntArray() else null
    }
}
