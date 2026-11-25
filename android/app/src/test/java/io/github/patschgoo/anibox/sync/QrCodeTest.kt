package io.github.patschgoo.anibox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    private fun ok(text: String) = (QrCode.parse(text) as QrResult.Ok).target

    @Test fun valid() {
        val t = ok("""{"v":1,"type":"anibox-sync","host":"MEDIABOX","share":"Drive A","path":"Anime/AniBox Sync","pc":"DESKTOP-TEST"}""")
        assertEquals(SyncTarget("MEDIABOX", "Drive A", "Anime/AniBox Sync", "DESKTOP-TEST", ""), t)
        assertEquals("\\\\MEDIABOX\\Drive A\\Anime\\AniBox Sync", t.display)
    }

    @Test fun withAddress() {
        val t = ok("""{"v":1,"type":"anibox-sync","host":"MEDIABOX","share":"Drive A","path":"AniBox Sync","pc":"PC","address":"192.0.2.10"}""")
        assertEquals("192.0.2.10", t.address)
    }

    @Test fun badAddressIsDropped() {
        assertEquals("", ok("""{"type":"anibox-sync","host":"H","share":"S","path":"p","address":"300.1.1.1"}""").address)
        assertEquals("", ok("""{"type":"anibox-sync","host":"H","share":"S","path":"p","address":42}""").address)
    }

    @Test fun pathIsCleaned() {
        assertEquals("Anime/AniBox Sync", ok("""{"type":"anibox-sync","host":"H","share":"S","path":"\\Anime\\AniBox Sync\\"}""").path)
    }

    @Test fun junk() {
        for (bad in listOf("", "   ", "hello", "{}", "[1,2]", """{"type":"other","host":"H","share":"S"}""", "https://example.com/x")) {
            assertTrue(bad, QrCode.parse(bad) is QrResult.Bad)
        }
    }

    @Test fun missingFields() {
        assertTrue(QrCode.parse("""{"v":1,"type":"anibox-sync","share":"S","path":"p"}""") is QrResult.Bad)
        assertTrue(QrCode.parse("""{"v":1,"type":"anibox-sync","host":"H","path":"p"}""") is QrResult.Bad)
        // No path: the share's top folder holds "AniBox Sync"
        assertEquals("", ok("""{"type":"anibox-sync","host":"H","share":"S"}""").path)
    }

    @Test fun newerVersion() {
        val r = QrCode.parse("""{"v":2,"type":"anibox-sync","host":"H","share":"S","path":"p"}""")
        assertTrue((r as QrResult.Bad).message.contains("newer"))
    }

    @Test fun pastedSharePath() {
        assertEquals(SyncTarget("MEDIABOX", "Drive A", "Anime/AniBox Sync"), ok("\\\\MEDIABOX\\Drive A\\Anime"))
        assertEquals(SyncTarget("MEDIABOX", "Drive A", "Anime/AniBox Sync"), ok("smb://MEDIABOX/Drive A/Anime/AniBox Sync"))
        assertTrue(QrCode.parse("\\\\MEDIABOX") is QrResult.Bad)
    }

    @Test fun typedFolderLikeThePc() {
        assertEquals("AniBox Sync", SyncTarget.syncFolder(""))
        assertEquals("Anime/AniBox Sync", SyncTarget.syncFolder("Anime"))
        assertEquals("Anime/aniBOX sync", SyncTarget.syncFolder("\\Anime\\aniBOX sync\\")) // kept as typed, like the PC
    }

    @Test fun ipv4() {
        assertTrue(QrCode.isIpv4("192.0.2.10"))
        for (bad in listOf("192.0.2", "192.0.2.256", "01.2.3.4x", "a.b.c.d", "")) assertTrue(bad, !QrCode.isIpv4(bad))
    }
}
