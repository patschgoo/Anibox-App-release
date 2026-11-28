package io.github.patschgoo.anibox.library

import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.Show

/** What you've watched and what comes next: the same rules as AniBox on the PC (app/anibox/watching.py). */
object Watching {
    /** Positions earlier than this start from the beginning. */
    const val RESUME_MIN_SECONDS = 30.0

    data class EpisodeState(val watched: Boolean, val position: Double, val duration: Double, val updated: Double) {
        val fraction: Float get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    }

    data class NextUp(val episode: Episode, val index: Int, val start: Double)

    fun state(e: Episode, progress: Map<String, LocalProgress>): EpisodeState {
        val p = progress[e.key]
        return EpisodeState(p?.watched ?: false, p?.position ?: 0.0, p?.duration ?: (e.duration ?: 0.0), p?.updated ?: 0.0)
    }

    /**
     * The episode to play next and where to start (seconds, before the "go back a bit"): the one you were
     * on if it isn't watched; else the next unwatched one after it; else the first unwatched one.
     * [available] leaves out episodes this phone can't play (only on the PC).
     */
    fun nextUp(show: Show, progress: Map<String, LocalProgress>, available: (Episode) -> Boolean = { true }): NextUp? {
        val eps = show.episodes
        if (eps.isEmpty()) return null
        val states = eps.map { state(it, progress) }
        fun start(i: Int) = states[i].position.let { if (it >= RESUME_MIN_SECONDS) it else 0.0 }
        val touched = states.indices.filter { states[it].updated > 0 }
        if (touched.isNotEmpty()) {
            val last = touched.maxBy { states[it].updated }
            if (!states[last].watched && available(eps[last])) return NextUp(eps[last], last, start(last))
            for (i in last + 1 until eps.size) {
                if (!states[i].watched && available(eps[i])) return NextUp(eps[i], i, start(i))
            }
        }
        for (i in eps.indices) {
            if (!states[i].watched && available(eps[i])) return NextUp(eps[i], i, start(i))
        }
        return null
    }

    /** Where playback starts: [back] seconds before the saved place; from the start if it was early or at the very end. */
    fun resumeFrom(position: Double, duration: Double, back: Int): Double {
        if (position < RESUME_MIN_SECONDS || (duration > 0 && position >= duration - 15)) return 0.0
        return maxOf(0.0, position - back)
    }

    data class Card(
        val show: Show,
        val watched: Int,
        val total: Int,
        val lastPlayed: Double,
        val next: NextUp?,
    ) {
        val started: Boolean get() = lastPlayed > 0
        val finished: Boolean get() = total > 0 && watched >= total
    }

    fun card(show: Show, progress: Map<String, LocalProgress>, available: (Episode) -> Boolean = { true }): Card {
        val eps = show.episodes
        val watched = eps.count { progress[it.key]?.watched == true }
        val last = eps.maxOfOrNull { progress[it.key]?.updated ?: 0.0 } ?: 0.0
        return Card(show, watched, eps.size, last, nextUp(show, progress, available))
    }

    /** Shows you've started that have a next episode, most recently played first. */
    fun continueWatching(
        shows: List<Show>,
        progress: Map<String, LocalProgress>,
        limit: Int = 20,
        available: (Episode) -> Boolean = { true },
    ): List<Card> = shows.asSequence()
        .filter { s -> s.episodes.any { (progress[it.key]?.updated ?: 0.0) > 0 } }
        .map { card(it, progress, available) }
        .filter { it.started && it.next != null }
        .sortedByDescending { it.lastPlayed }
        .take(limit)
        .toList()
}
