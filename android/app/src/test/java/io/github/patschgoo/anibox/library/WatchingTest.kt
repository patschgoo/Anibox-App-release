package io.github.patschgoo.anibox.library

import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as the PC's watching.py. */
class WatchingTest {
    private fun show(id: String, n: Int) = Show(
        id = "Anime\\$id", library = "Anime", folder = id, title = id,
        episodes = (1..n).map { Episode("Anime\\$id\\$id %02d.mkv".format(it), "$id %02d.mkv".format(it), label = "Episode $it", share = "s1", path = "x/$it.mkv") },
    )

    private val kaze = show("Kaze no Tabi", 4)
    private fun k(i: Int) = kaze.episodes[i - 1].key

    @Test fun nothingWatchedStartsAtOne() {
        val n = Watching.nextUp(kaze, emptyMap())!!
        assertEquals(k(1), n.episode.key)
        assertEquals(0.0, n.start, 0.0)
    }

    @Test fun resumesTheOneYouWereOn() {
        val p = mapOf(k(1) to LocalProgress(0.0, 1400.0, true, 10.0), k(2) to LocalProgress(600.0, 1400.0, false, 20.0))
        val n = Watching.nextUp(kaze, p)!!
        assertEquals(k(2), n.episode.key)
        assertEquals(600.0, n.start, 0.0)
    }

    @Test fun earlyPositionsStartFromTheBeginning() {
        val n = Watching.nextUp(kaze, mapOf(k(1) to LocalProgress(20.0, 1400.0, false, 5.0)))!!
        assertEquals(0.0, n.start, 0.0)
    }

    @Test fun afterRewatchingAnOldOneGoBackToWhereYouWere() {
        val p = mapOf(
            k(1) to LocalProgress(0.0, 1400.0, true, 30.0), // watched again last
            k(2) to LocalProgress(0.0, 1400.0, true, 10.0),
            k(3) to LocalProgress(400.0, 1400.0, false, 20.0),
        )
        // the last touched (1) is watched: the next unwatched after it is 3, with its place
        val n = Watching.nextUp(kaze, p)!!
        assertEquals(k(3), n.episode.key)
        assertEquals(400.0, n.start, 0.0)
    }

    @Test fun allWatched() {
        val p = kaze.episodes.associate { it.key to LocalProgress(0.0, 1400.0, true, 1.0) }
        assertNull(Watching.nextUp(kaze, p))
    }

    @Test fun skipsEpisodesOnlyOnThePc() {
        val n = Watching.nextUp(kaze, emptyMap()) { it.key != k(1) }!!
        assertEquals(k(2), n.episode.key)
    }

    @Test fun resumeGoesBackABit() {
        assertEquals(590.0, Watching.resumeFrom(600.0, 1400.0, 10), 0.0)
        assertEquals(0.0, Watching.resumeFrom(25.0, 1400.0, 10), 0.0) // early
        assertEquals(0.0, Watching.resumeFrom(1390.0, 1400.0, 10), 0.0) // at the very end
        assertEquals(600.0, Watching.resumeFrom(600.0, 0.0, 0), 0.0)
    }

    @Test fun continueWatchingNewestFirst() {
        val other = show("Hoshi", 2)
        val finished = show("Done", 1)
        val p = mapOf(
            k(1) to LocalProgress(500.0, 1400.0, false, 100.0),
            other.episodes[0].key to LocalProgress(0.0, 1400.0, true, 200.0),
            finished.episodes[0].key to LocalProgress(0.0, 1400.0, true, 300.0),
        )
        val cards = Watching.continueWatching(listOf(kaze, other, finished, show("Untouched", 3)), p)
        assertEquals(listOf("Anime\\Hoshi", "Anime\\Kaze no Tabi"), cards.map { it.show.id }) // finished and untouched left out
        assertEquals(other.episodes[1].key, cards[0].next!!.episode.key)
        assertEquals(1, cards[0].watched)
    }
}
