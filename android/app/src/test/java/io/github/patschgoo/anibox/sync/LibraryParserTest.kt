package io.github.patschgoo.anibox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryParserTest {
    private fun sample(): String = javaClass.classLoader!!.getResource("library-format1.json")!!.readText()

    private fun ok(text: String): Library = (LibraryParser.parse(text) as LibraryResult.Ok).library

    @Test fun readsFormat1() {
        val lib = ok(sample())
        assertEquals(1, lib.format)
        assertEquals("DESKTOP-TEST", lib.pc)
        assertEquals(listOf("Anime", "TV-Shows", "Movies"), lib.libraries.map { it.name })
        assertEquals("series", lib.kindOf("TV-Shows"))
        assertEquals(2, lib.shares.size)
        assertEquals("192.0.2.10", lib.share("s1a2b3c4d")!!.address)
        assertEquals("", lib.share("s5e6f7a8b")!!.address) // optional
        assertEquals(listOf("jpn", "ger"), lib.prefs!!.audioLangs)
        assertEquals("auto", lib.prefs!!.subs)

        val show = lib.show("Anime\\Kaze no Tabi")!!
        assertEquals("Journey of the Wind", show.englishTitle)
        assertEquals("風の旅", show.originalTitle)
        assertEquals("99901", show.malId)
        assertEquals("tv", show.atype)
        assertEquals(2021, show.year)
        assertEquals(listOf("Adventure", "Fantasy"), show.genres)
        assertEquals("0123456789ab-89abcdef.jpg", show.poster)
        assertEquals(1440.0, show.episodeLength!!, 0.0)
        assertEquals(3, show.episodes.size)

        val e1 = show.episodes[0]
        assertEquals("Kaze no Tabi 01.mkv", e1.fileName)
        assertEquals("The First Wind", e1.title)
        assertEquals(1420.0, e1.duration!!, 0.0)
        assertEquals("Anime/Kaze no Tabi/Kaze no Tabi 01.mkv", e1.path)
        assertTrue(lib.playable(e1))
        assertNull(show.episodes[1].duration) // null in the file

        val local = show.episodes[2]
        assertTrue(local.local)
        assertNull(local.share)
        assertFalse(lib.playable(local))

        val movie = lib.show("Anime\\Hoshi Movie")!!
        assertNull(movie.malId) // JSON null, not the text "null"
        assertNull(movie.year)
        assertEquals("", movie.poster)
        assertEquals(movie to 0, lib.episode("Anime\\Hoshi Movie\\Hoshi Movie.mkv"))

        val series = lib.show("TV-Shows\\Example Street")!!
        assertEquals(1, series.episodes[0].season)
        assertEquals("Example Street S01E01.mkv", series.episodes[0].fileName)
    }

    @Test fun missingOptionalFields() {
        val lib = ok(
            """{"format":1,"shows":[{"id":"Anime\\Solo","episodes":[{"key":"Anime\\Solo\\Solo 01.mkv","share":"sX","path":"a/b.mkv"}]}]}""",
        )
        assertNull(lib.prefs)
        assertTrue(lib.shares.isEmpty())
        val s = lib.shows.single()
        assertEquals("Solo", s.title) // falls back to the folder
        assertEquals("Anime", s.library)
        val e = s.episodes.single()
        assertEquals("Solo 01.mkv", e.file)
        assertFalse(lib.playable(e)) // the share isn't listed
    }

    @Test fun skipsBrokenEntries() {
        val lib = ok("""{"format":1,"shows":[{"title":"no id"},{"id":"A\\B","episodes":[{"file":"no key"},{"key":"A\\B\\c.mkv"}]}, 5]}""")
        assertEquals(1, lib.shows.size)
        assertEquals(1, lib.shows[0].episodes.size)
    }

    @Test fun badPosterNamesAreDropped() {
        val lib = ok("""{"format":1,"shows":[{"id":"A\\B","poster":"../../etc/passwd"},{"id":"A\\C","poster":".hidden.jpg"}]}""")
        assertTrue(lib.shows.all { it.poster.isEmpty() })
    }

    @Test fun newerFormat() {
        assertEquals(LibraryResult.TooNew(2), LibraryParser.parse("""{"format":2,"shows":[]}"""))
    }

    @Test fun brokenFile() {
        assertTrue(LibraryParser.parse("{not json") is LibraryResult.Broken)
        assertTrue(LibraryParser.parse("""{"shows":[]}""") is LibraryResult.Broken) // no format number
    }

    @Test fun badAddressesAreIgnored() {
        val lib = ok("""{"format":1,"shares":[{"id":"s1","host":"MEDIABOX","share":"Drive A","address":"not-an-ip"}],"shows":[]}""")
        assertEquals("", lib.shares[0].address)
    }

    @Test fun readsTheExtrasForThePhone() {
        val text = """{"format":1,"shows":[{"id":"Anime\\Vela","banner":"https://img.example/b.jpg",
            "audio":["ja"],"subs":["de","en"],"badges":["JA","SUB DE·EN"],"subWarn":true,
            "tv":{"plex":"convert","castapp":"ok"},"lock":"semi",
            "episodes":[{"key":"Anime\\Vela\\Vela 01.mkv","plot":"Something happens.","image":"http://not-https/x.jpg",
              "skip":[["opening",80.5,170.0],["ending",1300,1200],"bad"]}]}]}"""
        val show = ok(text).shows.single()
        assertEquals("https://img.example/b.jpg", show.banner)
        assertEquals(listOf("JA", "SUB DE·EN"), show.badges)
        assertTrue(show.subWarn)
        assertEquals(mapOf("plex" to "convert", "castapp" to "ok"), show.tv)
        assertEquals("semi", show.lock)
        val e = show.episodes.single()
        assertEquals("Something happens.", e.plot)
        assertEquals("", e.image) // only https pictures
        assertEquals(listOf(SkipMark("opening", 80.5, 170.0)), e.skip) // an end before its start is dropped
        val bare = ok("""{"format":1,"shows":[{"id":"A\\B","lock":"whatever","episodes":[]}]}""").shows.single()
        assertEquals("open", bare.lock)
        assertTrue(bare.tv.isEmpty())
    }
}
