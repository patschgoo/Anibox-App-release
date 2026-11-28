package io.github.patschgoo.anibox.library

import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.Show
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseTest {
    private fun s(title: String, kind: String = "anime", atype: String = "tv", year: Int? = null, sort: String = "", en: String = "") =
        Show(id = "L\\$title", library = "L", folder = title, title = title, kind = kind, atype = atype, year = year, sortTitle = sort, englishTitle = en,
            episodes = listOf(Episode("L\\$title\\1.mkv", "1.mkv")))

    private val shows = listOf(
        s("Kaze no Tabi 10", year = 2020, en = "Journey of the Wind"),
        s("Kaze no Tabi 2", year = 2018),
        s("Hoshi Movie", atype = "movie", year = 2022),
        s("Umi OVA", atype = "ova"),
        s("Example Street", kind = "series", atype = "", year = 1999),
        s("The Big Film", kind = "movie", atype = "", sort = "Big Film, The"),
    )

    @Test fun kindsAndTypes() {
        assertEquals(listOf(Kind.ANIME, Kind.SERIES, Kind.MOVIES), Browse.kinds(shows))
        assertEquals(listOf(Triple("", "All", 4), Triple("tv", "Series", 2), Triple("movie", "Movies", 1), Triple("ova", "OVAs", 1)), Browse.animeTypes(shows))
        assertEquals(listOf("Hoshi Movie"), Browse.filter(shows, Kind.ANIME, "movie", "").map { it.title })
        assertEquals(listOf("The Big Film"), Browse.filter(shows, Kind.MOVIES, "", "").map { it.title })
    }

    @Test fun search() {
        assertEquals(listOf("Kaze no Tabi 10"), Browse.filter(shows, null, "", "journey").map { it.title })
        assertEquals(2, Browse.filter(shows, Kind.ANIME, "", "KAZE-no").size) // punctuation and case don't matter
    }

    @Test fun sortByTitleNaturally() {
        val t = Browse.sort(shows.filter { it.kind == "anime" }, Sort.TITLE, emptyMap()).map { it.title }
        assertEquals(listOf("Hoshi Movie", "Kaze no Tabi 2", "Kaze no Tabi 10", "Umi OVA"), t)
        // the sort title wins
        assertEquals("The Big Film", Browse.sort(shows, Sort.TITLE, emptyMap()).first().title)
    }

    @Test fun sortByYearAndRecent() {
        assertEquals("Hoshi Movie", Browse.sort(shows, Sort.YEAR, emptyMap()).first().title)
        val p = mapOf("L\\Umi OVA\\1.mkv" to LocalProgress(1.0, 2.0, false, 99.0))
        assertEquals("Umi OVA", Browse.sort(shows, Sort.RECENT, p).first().title)
    }
}
