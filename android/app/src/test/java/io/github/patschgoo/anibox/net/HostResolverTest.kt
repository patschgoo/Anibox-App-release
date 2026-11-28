package io.github.patschgoo.anibox.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostResolverTest {
    private val calls = ArrayList<String>()

    private fun resolver(
        saved: String? = null,
        dns: Map<String, List<String>> = emptyMap(),
        netbios: String? = null,
        up: Set<String> = emptySet(),
    ) = HostResolver(
        saved = { calls += "saved $it"; saved },
        dns = { calls += "dns $it"; dns[it].orEmpty() },
        netbios = { calls += "netbios $it"; netbios },
        reachable = { calls += "try $it"; it in up },
    )

    @Test fun anAddressIsUsedAsItIs() {
        val c = resolver(up = setOf("192.0.2.10")).resolve("192.0.2.10", null)
        assertEquals(Candidate("192.0.2.10", Found.LITERAL), c)
        assertEquals(listOf("try 192.0.2.10"), calls)
    }

    @Test fun savedFirst() {
        val c = resolver(saved = "192.0.2.20", up = setOf("192.0.2.20")).resolve("MEDIABOX", "192.0.2.10")
        assertEquals(Candidate("192.0.2.20", Found.SAVED), c)
        assertEquals(listOf("saved MEDIABOX", "try 192.0.2.20"), calls) // nothing else asked
    }

    @Test fun thenThePcsAddress() {
        val c = resolver(saved = "192.0.2.20", up = setOf("192.0.2.10")).resolve("MEDIABOX", "192.0.2.10")
        assertEquals(Found.PC, c!!.how)
        assertEquals(listOf("saved MEDIABOX", "try 192.0.2.20", "try 192.0.2.10"), calls)
    }

    @Test fun thenDnsThenNetbios() {
        val r = resolver(dns = mapOf("MEDIABOX.local" to listOf("192.0.2.30")), netbios = "192.0.2.40", up = setOf("192.0.2.40"))
        val c = r.resolve("MEDIABOX", null)
        assertEquals(Candidate("192.0.2.40", Found.NETBIOS), c)
        assertEquals(
            listOf("saved MEDIABOX", "dns MEDIABOX", "dns MEDIABOX.local", "try 192.0.2.30", "netbios MEDIABOX", "try 192.0.2.40"),
            calls,
        )
    }

    @Test fun dnsWins() {
        val c = resolver(dns = mapOf("MEDIABOX" to listOf("192.0.2.30")), netbios = "192.0.2.40", up = setOf("192.0.2.30", "192.0.2.40"))
            .resolve("MEDIABOX", null)
        assertEquals(Found.DNS, c!!.how)
        assertEquals(listOf("saved MEDIABOX", "dns MEDIABOX", "try 192.0.2.30"), calls) // NetBIOS isn't asked
    }

    @Test fun nothingAnswersThenAsk() {
        assertNull(resolver(saved = "192.0.2.20", netbios = "192.0.2.20").resolve("MEDIABOX", "192.0.2.20"))
        assertEquals(1, calls.count { it == "try 192.0.2.20" }) // each address once
    }

    @Test fun aFullNameIsntTriedWithLocal() {
        resolver().resolve("mediabox.example.org", null)
        assertEquals(listOf("saved mediabox.example.org", "dns mediabox.example.org", "netbios mediabox.example.org"), calls)
    }

    @Test fun failingLookupsDontStopIt() {
        val r = HostResolver({ throw IllegalStateException() }, { throw RuntimeException() }, { "192.0.2.40" }, { true })
        assertEquals(Found.NETBIOS, r.resolve("MEDIABOX", null)!!.how)
    }
}
