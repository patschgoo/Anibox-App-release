package io.github.patschgoo.anibox.player

import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.JournalFormat
import io.github.patschgoo.anibox.sync.ProgressStore
import io.github.patschgoo.anibox.sync.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private var wall = 1_763_900_000.0
    private var mono = 0.0
    private val store by lazy { ProgressStore(tmp.root) { wall } }
    private val show = Show(
        id = "Anime\\Kaze no Tabi", library = "Anime", folder = "Kaze no Tabi", title = "Kaze no Tabi",
        episodes = (1..4).map { Episode("Anime\\Kaze no Tabi\\Kaze no Tabi 0$it.mkv", "Kaze no Tabi 0$it.mkv", label = "Episode $it", share = "s1", path = "a/$it.mkv", local = it == 3) },
    )
    private fun session() = PlaybackSession(show, store, { !it.local }, 10) { mono }
    private fun key(i: Int) = show.episodes[i - 1].key
    private fun lines() = JournalFormat.decodeAll(store.pending(), wall + 10)

    private fun advance(seconds: Double) {
        mono += seconds
        wall += seconds
    }

    @Test fun startsTenSecondsBefore() {
        store.savePosition(key(1), 600.0, 1400.0, journal = false)
        assertEquals(590.0, session().startFor(key(1)), 0.0)
        assertEquals(0.0, session().startFor(key(1), fromStart = true), 0.0)
        store.finished(key(1), 1400.0)
        assertEquals(0.0, session().startFor(key(1)), 0.0) // watched: from the start
        assertEquals(0.0, session().startFor(key(2)), 0.0) // never played
    }

    @Test fun journalAtMostEvery30Seconds() {
        val s = session()
        s.begin(key(1), 0.0)
        assertEquals(0, lines().size) // nothing until it really plays
        for (i in 1..59) {
            advance(1.0)
            s.tick(i.toDouble(), 1400.0)
        }
        assertEquals(2, lines().size) // the first tick, and one 30 s later
        assertEquals(59.0, store.get(key(1))!!.position, 5.0) // the place itself is kept every 5 s
        advance(1.0)
        s.tick(60.0, 1400.0)
        assertEquals(2, lines().size)
        advance(1.0)
        s.tick(61.0, 1400.0)
        assertEquals(3, lines().size)
    }

    @Test fun anOpenThatNeverPlaysChangesNothing() {
        store.savePosition(key(1), 600.0, 1400.0, journal = false)
        val before = store.get(key(1))!!
        val s = session()
        s.begin(key(1), s.startFor(key(1)))
        advance(5.0)
        s.pauseOrStop(590.0, 1400.0) // closed (or failed) before mpv said anything
        assertEquals(before, store.get(key(1)))
        assertEquals(0, lines().size)
    }

    @Test fun pauseAndStopWriteAtOnce() {
        val s = session()
        s.begin(key(1), 0.0)
        advance(3.0)
        s.tick(3.0, 1400.0)
        s.pauseOrStop(3.5, 1400.0)
        assertEquals(2, lines().size)
        assertEquals(3.5, lines().last().position, 0.0)
    }

    @Test fun watchedAt95PercentRightAway() {
        val s = session()
        s.begin(key(1), 1300.0)
        advance(1.0)
        assertTrue(s.tick(1330.0, 1400.0))
        assertTrue(lines().last().watched)
        assertTrue(store.get(key(1))!!.watched)
        advance(1.0)
        assertFalse(s.tick(1331.0, 1400.0)) // only once
    }

    @Test fun endOffersTheNextPlayableOne() {
        val s = session()
        s.begin(key(2), 0.0)
        val next = s.ended(1400.0)
        assertEquals(key(4), next!!.key) // 3 is only on the PC
        val e = store.get(key(2))!!
        assertTrue(e.watched)
        assertEquals(0.0, e.position, 0.0)
        assertTrue(lines().last().watched)
        s.begin(key(4), 0.0)
        assertNull(s.neighbour(1))
        assertEquals(key(2), s.neighbour(-1)!!.key)
    }

    @Test fun rewatchingKeepsTheMark() {
        store.finished(key(1), 1400.0)
        val s = session()
        s.begin(key(1), 0.0)
        advance(31.0)
        s.tick(31.0, 1400.0)
        assertTrue(store.get(key(1))!!.watched)
        assertTrue(lines().all { it.watched })
    }
}
