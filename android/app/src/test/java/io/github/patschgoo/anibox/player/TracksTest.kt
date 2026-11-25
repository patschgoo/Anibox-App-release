package io.github.patschgoo.anibox.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TracksTest {
    @Test fun likeThePcsPlayer() {
        val c = TrackPrefs.choose(listOf("jpn", "ger"), listOf("ger", "eng"), "auto")
        assertEquals("jpn,ja,jp,ger,deu,de", c.alang)
        assertEquals("ger,deu,de,eng,en", c.slang)
        assertEquals("auto", c.sid)
        assertEquals("default", c.subsFallback)
    }

    @Test fun modes() {
        assertEquals("no", TrackPrefs.choose(emptyList(), listOf("ger"), "off").sid)
        assertEquals("yes", TrackPrefs.choose(emptyList(), listOf("ger"), "on").subsFallback)
    }

    @Test fun theShowsOwnPickComesFirst() {
        val c = TrackPrefs.choose(listOf("jpn"), listOf("ger"), "auto", ShowLanguage(audio = "eng", sub = "eng"))
        assertEquals("eng,en,jpn,ja,jp", c.alang)
        assertEquals("eng,en,ger,deu,de", c.slang)
        val off = TrackPrefs.choose(listOf("jpn"), listOf("ger"), "auto", ShowLanguage(sub = "no"))
        assertEquals("no", off.sid)
        assertEquals("ger,deu,de", off.slang)
    }

    @Test fun cleansCodes() {
        assertEquals(listOf("jpn", "ger"), Languages.clean("jpn, Ger ;  nonsense-word-x, 12"))
        assertEquals("de", Languages.two("deu"))
        assertEquals("German", Languages.name("ger"))
    }

    @Test fun sidecarsBestFirst() {
        val names = listOf(
            "Kaze no Tabi 01.mkv", "Kaze no Tabi 01.eng.srt", "Kaze no Tabi 01.ger.forced.ass", "Kaze no Tabi 01.ger.ass",
            "Kaze no Tabi 01.ger.srt", "Kaze no Tabi 01.srt", "Kaze no Tabi 01.sup", "Kaze no Tabi 10.ger.srt",
            "Kaze no Tabi 01 extended.mkv", "Kaze no Tabi 01 extended.ger.srt", "Kaze no Tabi 01.fre.srt", "poster.jpg",
        )
        val found = Sidecars.find("Kaze no Tabi 01.mkv", names, "Anime/Kaze no Tabi", listOf("ger", "eng"))
        assertEquals(
            listOf(
                "Kaze no Tabi 01.ger.srt", "Kaze no Tabi 01.ger.ass", "Kaze no Tabi 01.ger.forced.ass",
                "Kaze no Tabi 01.eng.srt", "Kaze no Tabi 01.srt", "Kaze no Tabi 01.sup", "Kaze no Tabi 01.fre.srt",
            ),
            found.map { it.name },
        )
        assertEquals("Anime/Kaze no Tabi/Kaze no Tabi 01.ger.srt", found[0].path)
        assertEquals("de", found[0].lang)
        assertEquals(true, found[2].forced)
        assertEquals("ger", Sidecars.mpvLang("de"))
    }

    @Test fun belongsRule() {
        assertEquals(true, Sidecars.belongs("Ep 1.en", "Ep 1"))
        assertEquals(true, Sidecars.belongs("Ep 1 [eng]", "Ep 1"))
        assertEquals(false, Sidecars.belongs("Ep 10", "Ep 1"))
    }
}
