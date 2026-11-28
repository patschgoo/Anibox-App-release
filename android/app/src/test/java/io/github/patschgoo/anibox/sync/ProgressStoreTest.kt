package io.github.patschgoo.anibox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProgressStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var time = 1_763_900_000.0
    private fun store() = ProgressStore(tmp.root) { time }
    private val k = "Anime\\Kaze no Tabi\\Kaze no Tabi 01.mkv"

    @Test fun newerWins() {
        val s = store()
        s.savePosition(k, 100.0, 1400.0, journal = false)
        assertEquals(1, s.merge(listOf(ProgressLine(k, 500.0, 1400.0, false, time + 10))))
        assertEquals(500.0, s.get(k)!!.position, 0.0)
        // older: ignored
        assertEquals(0, s.merge(listOf(ProgressLine(k, 50.0, 1400.0, false, time - 10))))
        assertEquals(500.0, s.get(k)!!.position, 0.0)
        // the same time: ignored (the PC reflects this phone's own line back with its time)
        assertEquals(0, s.merge(listOf(ProgressLine(k, 7.0, 1400.0, false, time + 10))))
    }

    @Test fun aNewerUnmarkByHandTravels() {
        val s = store()
        s.finished(k, 1400.0)
        // un-marked by hand on the PC, later than this phone's mark
        assertEquals(1, s.merge(listOf(ProgressLine(k, 0.0, 1400.0, false, time + 10, unmarked = true))))
        assertFalse(s.get(k)!!.watched)
        assertTrue(s.get(k)!!.unmarkedByHand)
        // an older watched line from elsewhere doesn't bring it back
        assertEquals(0, s.merge(listOf(ProgressLine(k, 0.0, 1400.0, true, time + 5))))
        assertFalse(s.get(k)!!.watched)
        // this phone's own un-mark says so in its journal line (only that line)
        val k2 = "Anime\\Kaze no Tabi\\Kaze no Tabi 02.mkv"
        s.setWatched(listOf(k2), false)
        time += 5
        s.savePosition(k2, 30.0, 1400.0, journal = true)
        val sent = JournalFormat.decodeAll(s.pending(), time + 10).filter { it.key == k2 }
        assertEquals(listOf(true, false), sent.map { it.unmarked })
    }

    @Test fun syncNeverUnmarks() {
        val s = store()
        s.merge(listOf(ProgressLine(k, 0.0, 1400.0, true, time)))
        assertTrue(s.get(k)!!.watched)
        s.merge(listOf(ProgressLine(k, 300.0, 1400.0, false, time + 50)))
        val e = s.get(k)!!
        assertTrue(e.watched) // still watched
        assertEquals(300.0, e.position, 0.0) // the newer place is taken
    }

    @Test fun olderWatchedMarkStillMarks() {
        val s = store()
        s.savePosition(k, 20.0, 1400.0, journal = false) // started here, never watched here
        time += 100
        s.merge(listOf(ProgressLine(k, 0.0, 1400.0, true, time - 1000))) // watched on the PC long ago
        assertTrue(s.get(k)!!.watched)
        assertEquals(20.0, s.get(k)!!.position, 0.0)
    }

    @Test fun handUnmarkSticks() {
        val s = store()
        s.merge(listOf(ProgressLine(k, 0.0, 1400.0, true, time)))
        time += 60
        s.setWatched(listOf(k), false) // by hand on this phone
        assertFalse(s.get(k)!!.watched)
        assertTrue(s.get(k)!!.unmarkedByHand)
        // the PC's older mark, and the PC echoing the un-mark's time with its own mark kept: both stay out
        s.merge(listOf(ProgressLine(k, 0.0, 1400.0, true, time - 60)))
        s.merge(listOf(ProgressLine(k, 0.0, 1400.0, true, time)))
        assertFalse(s.get(k)!!.watched)
        // watched again later somewhere: that counts
        s.merge(listOf(ProgressLine(k, 0.0, 1400.0, true, time + 500)))
        assertTrue(s.get(k)!!.watched)
        assertFalse(s.get(k)!!.unmarkedByHand)
        // and the un-mark wrote a journal line with watched false
        val lines = JournalFormat.decodeAll(s.pending(), time + 1000)
        assertEquals(1, lines.size) // merges from elsewhere don't go into this phone's journal
        assertEquals(false, lines.single().watched)
    }

    @Test fun watchedAt95Percent() {
        val s = store()
        assertFalse(s.savePosition(k, 1300.0, 1400.0, journal = true).watched)
        assertTrue(s.savePosition(k, 1330.0, 1400.0, journal = true).watched) // 95 %
        // watching it again keeps the mark
        assertTrue(s.savePosition(k, 10.0, 1400.0, journal = true).watched)
        val f = s.finished(k, 1400.0)
        assertTrue(f.watched)
        assertEquals(0.0, f.position, 0.0)
    }

    @Test fun markByHandStartsOver() {
        val s = store()
        s.savePosition(k, 600.0, 1400.0, journal = false)
        s.setWatched(listOf(k), true)
        assertEquals(0.0, s.get(k)!!.position, 0.0)
        assertEquals(1400.0, s.get(k)!!.duration, 0.0)
    }

    @Test fun keptAcrossRestarts() {
        val s = store()
        s.savePosition(k, 123.4, 1400.0, journal = true)
        s.setWatched(listOf("x\\y\\z.mkv"), false)
        s.save()
        val again = store()
        assertEquals(123.4, again.get(k)!!.position, 0.0)
        assertTrue(again.get("x\\y\\z.mkv")!!.unmarkedByHand)
        assertEquals(2, again.pendingCount()) // the queue is kept too
    }

    @Test fun queueSentPartly() {
        val s = store()
        s.savePosition(k, 1.0, 1400.0, journal = true)
        val first = s.pending()
        s.savePosition(k, 2.0, 1400.0, journal = true) // queued while the first went out
        s.sent(first.size)
        val rest = JournalFormat.decodeAll(s.pending(), time)
        assertEquals(1, rest.size)
        assertEquals(2.0, rest[0].position, 0.0)
    }

    @Test fun updatedIsKeptToTheMillisecond() {
        time = 1_763_900_000.123456
        val s = store()
        s.savePosition(k, 1.0, 2.0, journal = true)
        assertEquals(1_763_900_000.123, s.get(k)!!.updated, 0.0)
        val line = JournalFormat.decodeAll(s.pending(), time).single()
        assertEquals(s.get(k)!!.updated, line.updated, 0.0) // exactly what the PC will echo back
    }

    @Test fun nothingForUnknown() {
        assertNull(store().get("nope"))
    }

    @Test fun eachProfileKeepsItsOwnPlaces() {
        val s = store()
        s.savePosition(k, 100.0, 1400.0, journal = true)
        s.switchProfile("p2")
        assertNull(s.get(k))
        // only this profile's lines are taken from the PC
        s.merge(listOf(ProgressLine(k, 300.0, 1400.0, false, time + 5, "p2"), ProgressLine(k, 900.0, 1400.0, false, time + 9, "main")))
        assertEquals(300.0, s.get(k)!!.position, 0.0)
        s.savePosition(k, 310.0, 1400.0, journal = true)
        assertEquals("p2", JournalFormat.decodeAll(s.pending(), time + 100).last().profile)
        s.save()
        s.switchProfile("main")
        assertEquals(100.0, s.get(k)!!.position, 0.0)
        s.switchProfile("P2") // ids aren't case-sensitive
        assertEquals(310.0, s.get(k)!!.position, 0.0)
    }
}
