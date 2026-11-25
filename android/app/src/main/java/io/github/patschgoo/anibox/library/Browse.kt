package io.github.patschgoo.anibox.library

import io.github.patschgoo.anibox.net.NaturalOrder
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.Show
import java.text.Normalizer

/** The Library page's choices, like on the PC: Anime (with its MyAnimeList types), Series, Movies. */
enum class Kind(val label: String) { ANIME("Anime"), SERIES("Series"), MOVIES("Movies") }

/** The anime types (MyAnimeList's, lower case) and their names on the PC. "" = all. */
val ANIME_TYPES: List<Pair<String, String>> = listOf(
    "" to "All", "tv" to "Series", "movie" to "Movies", "ova" to "OVAs", "special" to "Specials", "ona" to "ONAs", "music" to "Music videos",
)

enum class Sort(val label: String) { TITLE("Title"), YEAR("Year"), RECENT("Recently watched") }

object Browse {
    fun kindOf(show: Show): Kind = when (show.kind) {
        "anime" -> Kind.ANIME
        "movie", "movies" -> Kind.MOVIES
        else -> Kind.SERIES
    }

    /** The kinds that have shows (in the PC's order). */
    fun kinds(shows: List<Show>): List<Kind> = Kind.entries.filter { k -> shows.any { kindOf(it) == k } }

    /** The anime types present, with how many shows each has (always "All" first). */
    fun animeTypes(shows: List<Show>): List<Triple<String, String, Int>> {
        val anime = shows.filter { kindOf(it) == Kind.ANIME }
        val counts = anime.groupingBy { it.atype }.eachCount()
        return ANIME_TYPES.mapNotNull { (key, label) ->
            if (key.isEmpty()) Triple(key, label, anime.size) else counts[key]?.let { Triple(key, label, it) }
        }
    }

    fun filter(shows: List<Show>, kind: Kind?, atype: String, query: String): List<Show> {
        val q = fold(query)
        return shows.filter { s ->
            (kind == null || kindOf(s) == kind) &&
                (kind != Kind.ANIME || atype.isEmpty() || s.atype == atype) &&
                (q.isEmpty() || matches(s, q))
        }
    }

    private fun matches(s: Show, q: String): Boolean =
        listOf(s.title, s.englishTitle, s.originalTitle, s.folder, s.sortTitle).any { fold(it).contains(q) }

    /** Lower case, accents and punctuation ignored ("Kaze no Tabi!" finds "kaze no tabi"). */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /** The name a show is sorted by: its sort title, else its title (a leading "The"/"A" is kept, like the PC). */
    fun sortName(s: Show): String = s.sortTitle.ifEmpty { s.title }

    val byTitle: Comparator<Show> = Comparator<Show> { a, b -> NaturalOrder.compare(sortName(a), sortName(b)) }
        .thenBy { it.id }

    fun sort(shows: List<Show>, sort: Sort, progress: Map<String, LocalProgress>): List<Show> = when (sort) {
        Sort.TITLE -> shows.sortedWith(byTitle)
        Sort.YEAR -> shows.sortedWith(compareByDescending<Show> { it.year ?: Int.MIN_VALUE }.then(byTitle))
        Sort.RECENT -> {
            val last = shows.associate { s -> s.id to (s.episodes.maxOfOrNull { progress[it.key]?.updated ?: 0.0 } ?: 0.0) }
            shows.sortedWith(compareByDescending<Show> { last[it.id] ?: 0.0 }.then(byTitle))
        }
    }
}
