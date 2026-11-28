package io.github.patschgoo.anibox.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Doesn't answer" in plain English, by how the phone is connected. Made-up addresses only. */
class ReachTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun homeAndTailscaleAddresses() {
        for (a in listOf("10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.0.20", "169.254.7.7")) assertTrue(a, Reach.isHome(a))
        for (a in listOf("172.32.0.1", "8.8.8.8", "203.0.113.5", "100.70.1.2", "MEDIABOX", "300.1.1.1")) assertFalse(a, Reach.isHome(a))
        assertTrue(Reach.isTailscale("100.64.0.1"))
        assertTrue(Reach.isTailscale("100.127.255.254"))
        assertFalse(Reach.isTailscale("100.128.0.1"))
    }

    @Test fun aHomeAddressOnMobileDataSaysWiFiOrTailscale() {
        val p = Reach.notReached("192.168.0.20", Link(Link.Kind.MOBILE))
        assertEquals(Problem.NO_ANSWER, p.problem)
        val m = p.message!!
        assertTrue(m, m.contains("mobile data"))
        assertTrue(m, m.contains("home network"))
        assertTrue(m, m.contains("Tailscale"))
        assertFalse(m, m.contains("Type its address"))
    }

    @Test fun eachWayOfBeingConnected() {
        assertTrue(Reach.notReached("192.168.0.20", Link(Link.Kind.WIFI)).message!!.contains("guest network"))
        assertTrue(Reach.notReached("192.168.0.20", Link(Link.Kind.MOBILE, vpn = true)).message!!.contains("through the VPN"))
        assertTrue(Reach.notReached("192.168.0.20", Link(Link.Kind.NONE)).message!!.contains("isn't connected"))
        assertTrue(Reach.notReached("100.70.1.2", Link(Link.Kind.MOBILE)).message!!.contains("Tailscale address"))
        assertTrue(Reach.notReached("192.168.0.20", Link.UNKNOWN).message!!.contains("home Wi-Fi"))
        assertTrue(Reach.notReached("203.0.113.5", Link(Link.Kind.WIFI)).message!!.contains("same network"))
    }

    @Test fun aNameStillAsksForItsAddressExceptOnMobileData() {
        val wifi = Reach.notReached("MEDIABOX", Link(Link.Kind.WIFI))
        assertEquals(Problem.HOST_NOT_FOUND, wifi.problem)
        assertTrue(wifi.message!!.contains("Type its address"))
        val mobile = Reach.notReached("MEDIABOX", Link(Link.Kind.MOBILE))
        assertEquals(Problem.HOST_NOT_FOUND, mobile.problem)
        assertTrue(mobile.message!!.contains("mobile data"))
    }

    @Test fun shareAccessWordsItByTheLinkAtThatMoment() {
        var link = Link(Link.Kind.MOBILE)
        val book = object : AddressBook {
            override fun address(host: String): String? = null
            override fun remember(host: String, address: String, how: Found) {}
        }
        val resolver = HostResolver({ null }, { emptyList() }, { null }, { false })
        val shares = ShareAccess(Credentials(File(tmp.root, "signin.json"), { it }, { it }), book, resolver) { link }
        val first = runCatching { shares.address("192.168.0.20", null) }.exceptionOrNull() as SmbProblem
        assertEquals(Problem.NO_ANSWER, first.problem)
        assertTrue(first.message!!.contains("mobile data"))
        link = Link(Link.Kind.WIFI) // looked for a moment ago: not looked for again, but worded for now
        val again = runCatching { shares.address("192.168.0.20", null) }.exceptionOrNull() as SmbProblem
        assertTrue(again.message!!.contains("on this Wi-Fi"))
    }
}
